import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diff.impl.fragments.LineFragment
import com.intellij.openapi.diff.impl.processing.TextCompareProcessor
import com.intellij.openapi.diff.impl.util.TextDiffTypeEnum
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.FilePathImpl
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import org.jetbrains.annotations.NotNull

import java.text.SimpleDateFormat

import static com.intellij.openapi.diff.impl.ComparisonPolicy.IGNORE_SPACE
import static com.intellij.openapi.diff.impl.highlighting.FragmentSide.SIDE1
import static com.intellij.openapi.diff.impl.highlighting.FragmentSide.SIDE2
import static com.intellij.openapi.diff.impl.util.TextDiffTypeEnum.DELETED
import static intellijeval.PluginUtil.*

if (isIdeStartup) return

for (VirtualFile file in allFilesIn_(project)) {
	show(file.path)
}

static Iterator<VirtualFile> allFilesIn_(@NotNull Project project) {
	def sourceRoots = ProjectRootManager.getInstance(project).contentSourceRoots
	def modules = ModuleManager.getInstance(project).modules
	def exclusions = modules.collectMany { Module module -> ModuleRootManager.getInstance(module).excludeRoots.toList() }

	def queue = new LinkedList<VirtualFile>(sourceRoots.toList())

	new Iterator<VirtualFile>() {
		@Override boolean hasNext() { !queue.empty }

		@Override VirtualFile next() {
			if (queue.first.isDirectory() && !exclusions.contains(queue.first))
				queue.addAll(queue.first.children)
			queue.removeFirst()
		}

		@Override void remove() { throw new UnsupportedOperationException() }
	}
}

if (true) return

def file = currentFileIn(project)
def (errorMessage, List<VcsFileRevision> revisions) = tryToGetHistoryFor(file, project)
if (errorMessage != null) {
	show(errorMessage)
	return
}
show("good to go")

def changeEvents = extractChangeEvents(file, revisions, project)
show(toCsv(changeEvents))
save(toCsv(changeEvents), "${PathManager.pluginsPath}/delta-flora/stats.csv")

static save(String csv, String fileName) {
	FileUtil.writeToFile(new File(fileName), csv)
}

static String toCsv(List<List> changeEvents) {
	changeEvents.collect{toCsvLine(it)}.join("\n")
}
static String toCsvLine(List changeEvent) {
	def eventsAsString = changeEvent.collect {
		if (it instanceof Date) format((Date) it)
		else if (it instanceof TextDiffTypeEnum) format((TextDiffTypeEnum) it)
		else asString(it)
	}
	eventsAsString[eventsAsString.size() - 1] = '"' + eventsAsString.last().replaceAll("\"", "\\\"") + '"'
	eventsAsString.join(",")
}

static List<List> extractChangeEvents(VirtualFile file, List<VcsFileRevision> revisions, Project project) {
	def revisionPairs = (0..<revisions.size() - 1).collect { revisions[it, it + 1] }
	def compareProcessor = new TextCompareProcessor(IGNORE_SPACE)
	def psiFileFactory = PsiFileFactory.getInstance(project)
	def parseAsPSI = { VcsFileRevision revision -> psiFileFactory.createFileFromText(file.name, file.fileType, new String(revision.content)) }

	revisionPairs.collectMany { VcsFileRevision before, VcsFileRevision after ->
		def beforeText = new String(before.content)
		def afterText = new String(after.content)
		def psiBefore = parseAsPSI(before)
		def psiAfter = parseAsPSI(after)

		def changedFragments = compareProcessor.process(beforeText, afterText).findAll { it.type != null }
		changedFragments.collectMany { LineFragment fragment ->
			def offsetToLineNumber = { int offset -> fragment.type == DELETED ? toLineNumber(offset, beforeText) : toLineNumber(offset, afterText) }

			def revisionWithCode = (fragment.type == DELETED ? psiBefore : psiAfter)
			def range = (fragment.type == DELETED ? fragment.getRange(SIDE1) : fragment.getRange(SIDE2))

			def changeEvents = []
			def prevPsiElement = null
			for (int offset in range.startOffset..<range.endOffset) {
				PsiNamedElement psiElement = methodOrClassAt(offset, revisionWithCode)
				if (psiElement != prevPsiElement) {
					changeEvents << [
							fullNameOf(psiElement),
							after.revisionNumber.asString(),
							after.author,
							after.revisionDate,
							containingFileName(psiElement),
							fragment.type,
							offsetToLineNumber(offset),
							offsetToLineNumber(offset + 1),
							offset,
							offset + 1,
							after.commitMessage
					]
					prevPsiElement = psiElement
				} else {
					changeEvents.last()[7] = offsetToLineNumber(offset + 1)
					changeEvents.last()[9] = offset + 1
				}
			}
			changeEvents
		}
	}
}

static String format(Date date) {
	new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format(date)
}

static String format(TextDiffTypeEnum diffType) {
	switch (diffType) {
		case TextDiffTypeEnum.INSERT: return "added"
		case TextDiffTypeEnum.CHANGED: return "changed"
		case TextDiffTypeEnum.DELETED: return "deleted"
		case TextDiffTypeEnum.CONFLICT: return "conflict"
		case TextDiffTypeEnum.NONE: return "none"
		default: return "unknown"
	}
}

static int toLineNumber(int offset, String text) {
	int counter = 0
	for (int i = 0; i < offset; i++) {
		if (text.charAt(i) == '\n') counter++
	}
	counter
}

static String containingFileName(PsiElement psiElement) {
	if (psiElement == null) "null"
	else if (psiElement instanceof PsiFile) psiElement.name
	else (containingFileName(psiElement.parent))
}

static String fullNameOf(PsiElement psiElement) {
	if (psiElement == null) "null"
	else if (psiElement instanceof PsiFile) ""
	else if (psiElement instanceof PsiMethod || psiElement instanceof PsiClass) {
		def parentName = fullNameOf(psiElement.parent)
		parentName.empty ? psiElement.name : (parentName + "::" + psiElement.name)
	} else {
		fullNameOf(psiElement.parent)
	}
}

static PsiNamedElement methodOrClassAt(int offset, PsiFile psiFile) {
	parentMethodOrClassOf(psiFile.findElementAt(offset))
}

static PsiNamedElement parentMethodOrClassOf(PsiElement psiElement) {
	if (psiElement instanceof PsiMethod) psiElement as PsiNamedElement
	else if (psiElement instanceof PsiClass) psiElement as PsiNamedElement
	else if (psiElement instanceof PsiFile) psiElement as PsiNamedElement
	else parentMethodOrClassOf(psiElement.parent)
}

static tryToGetHistoryFor(VirtualFile file, Project project) {
	if (file == null) return ["Virtual file was null"]

	AbstractVcs activeVcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file)
	if (activeVcs == null) return ["There is no history for '${file.name}'"]

	def historySession = activeVcs.vcsHistoryProvider.createSessionFor(new FilePathImpl(file))
	def revisions = historySession.revisionList.sort{ it.revisionDate }
	if (revisions.size() < 2) return ["There is only one revision for '${file.name}'"]

	def noErrors = null
	[noErrors, revisions]
}
