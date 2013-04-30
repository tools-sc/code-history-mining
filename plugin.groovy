import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diff.impl.fragments.LineFragment
import com.intellij.openapi.diff.impl.processing.TextCompareProcessor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList
import com.intellij.psi.*
import history.Measure
import history.ProjectHistory
import org.jetbrains.annotations.Nullable

import java.text.SimpleDateFormat


import static ChangeExtractor.*
import static com.intellij.openapi.diff.impl.ComparisonPolicy.TRIM_SPACE
import static com.intellij.openapi.diff.impl.highlighting.FragmentSide.SIDE1
import static com.intellij.openapi.diff.impl.highlighting.FragmentSide.SIDE2
import static com.intellij.openapi.diff.impl.util.TextDiffTypeEnum.*
import static com.intellij.util.text.DateFormatUtil.getDateFormat
import static history.Events.*
import static history.Measure.measure
import static intellijeval.PluginUtil.*

if (isIdeStartup) return

//new TextCompareProcessorTestSuite(project).run()
//if (true) return


doInBackground("Analyzing project history", { ProgressIndicator indicator ->
	measure("time") {
		def storage = new EventStorage("${PathManager.pluginsPath}/delta-flora/${project.name}-events.csv")

		def now = new Date()
		def daysOfHistory = 900
		def sizeOfVCSRequestInDays = 1

		if (storage.hasNoEvents()) {
			def historyStart = now - daysOfHistory
			def historyEnd = now

			log("Loading project history from $historyStart to $historyEnd")
			Iterator<CommittedChangeList> changeLists = ProjectHistory.fetchChangeListsFor(project, historyStart, historyEnd, sizeOfVCSRequestInDays)
			processChangeLists(changeLists, indicator) { changeEvents ->
				storage.appendToEventsFile(changeEvents)
			}
		} else {
			def historyStart = storage.mostRecentEventTime
			def historyEnd = now
			log("Loading project history from $historyStart to $historyEnd")

			def changeLists = ProjectHistory.fetchChangeListsFor(project, historyStart, historyEnd, sizeOfVCSRequestInDays, false)
			processChangeLists(changeLists, indicator) { changeEvents ->
				storage.prependToEventsFile(changeEvents)
			}

			historyStart = now - daysOfHistory
			historyEnd = storage.oldestEventTime
			log("Loading project history from $historyStart to $historyEnd")

			changeLists = ProjectHistory.fetchChangeListsFor(project, historyStart, historyEnd, sizeOfVCSRequestInDays)
			processChangeLists(changeLists, indicator) { changeEvents ->
				storage.appendToEventsFile(changeEvents)
			}
		}

		showInConsole("Saved change events to ${storage.filePath}", "output", project)
		showInConsole("(it should have history from '${storage.oldestEventTime}' to '${storage.mostRecentEventTime}')", "output", project)
	}
	Measure.durations.entrySet().collect{ "Total " + it.key + ": " + it.value }.each{ log(it) }
}, {})

def processChangeLists(changeLists, indicator, callback) {
	for (changeList in changeLists) {
		if (changeList == null) break
		if (indicator.canceled) break
		log(changeList.name)

		def date = dateFormat.format((Date) changeList.commitDate)
		indicator.text = "Analyzing project history (${date} - '${changeList.comment.trim()}')"
		catchingAll_ {
//			Collection<ChangeEvent> changeEvents = fileChangeEventsFrom((CommittedChangeList) changeList, project)
			Collection<ChangeEvent> changeEvents = changeEventsFrom((CommittedChangeList) changeList, project)
			callback(changeEvents)
		}
		indicator.text = "Analyzing project history (${date} - looking for next commit...)"
	}
}

@Nullable static <T> T catchingAll_(Closure<T> closure) {
	try {
		closure.call()
	} catch (Exception e) {
		log(e)
		null
	}
}


class EventStorage {
	static final String CSV_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss Z"

	final String filePath

	EventStorage(String filePath) {
		this.filePath = filePath
	}

	List<ChangeEvent> readAllEvents() {
		def events = []
		new File(filePath).withReader { reader ->
			def line = null
			while ((line = reader.readLine()) != null) {
				try {
					events << fromCsv(line)
				} catch (Exception ignored) {
					println("Failed to parse line '${line}'")
				}
			}
		}
		events
	}

	def appendToEventsFile(Collection<ChangeEvent> changeEvents) {
		if (changeEvents.empty) return
		appendTo(filePath, toCsv(changeEvents))
	}

	def prependToEventsFile(Collection<ChangeEvent> changeEvents) {
		if (changeEvents.empty) return
		prependTo(filePath, toCsv(changeEvents))
	}

	Date getOldestEventTime() {
		def line = readLastLine(filePath)
		if (line == null) null
		else {
			// minus one second because git "before" seems to be inclusive (even though ChangeBrowserSettings API is exclusive)
			// (it means that if processing stops between two commits that happened on the same second,
			// we will miss one of them.. considered this to be insignificant)
			def date = fromCsv(line).revisionDate
			date.time -= 1000
			date
		}
	}

	Date getMostRecentEventTime() {
		def line = readFirstLine(filePath)
		if (line == null) new Date()
		else {
			def date = fromCsv(line).revisionDate
			date.time += 1000 // plus one second (see comments in getOldestEventTime())
			date
		}
	}

	boolean hasNoEvents() {
		def file = new File(filePath)
		!file.exists() || file.length() == 0
	}

	private static String toCsv(Collection<ChangeEvent> changeEvents) {
		changeEvents.collect{toCsv(it)}.join("\n") + "\n"
	}

	private static String toCsv(ChangeEvent changeEvent) {
		changeEvent.with {
			def commitMessageEscaped = '"' + commitMessage.replaceAll("\"", "\\\"").replaceAll("\n", "\\\\n") + '"'
			[format(revisionDate), revision, author, elementName.replaceAll(",", ""),
					fileName, fileChangeType, packageBefore, packageAfter, linesInFileBefore, linesInFileAfter,
					changeType, fromLine, toLine, fromOffset, toOffset, commitMessageEscaped].join(",")
		}
	}

	private static ChangeEvent fromCsv(String line) {
		def (revisionDate, revision, author, elementName,
				fileName, fileChangeType, packageBefore, packageAfter, linesInFileBefore, linesInFileAfter,
				changeType, fromLine, toLine, fromOffset, toOffset) = line.split(",")
		revisionDate = new SimpleDateFormat(CSV_DATE_FORMAT).parse(revisionDate)
		def commitMessage = line.substring(line.indexOf('"') + 1, line.size() - 1)

		def event = new ChangeEvent(
				new CommitInfo(revision, author, revisionDate, commitMessage),
				new FileChangeInfo(fileName, fileChangeType, packageBefore, packageAfter, linesInFileBefore.toInteger(), linesInFileAfter.toInteger()),
				new ElementChangeInfo(elementName, changeType, fromLine.toInteger(), toLine.toInteger(), fromOffset.toInteger(), toOffset.toInteger())
		)
		event
	}

	private static String format(Date date) {
		new SimpleDateFormat(CSV_DATE_FORMAT).format(date)
	}

	private static String readFirstLine(String filePath) {
		def file = new File(filePath)
		if (!file.exists() || file.length() == 0) return null
		file.withReader{ it.readLine() }
	}

	private static String readLastLine(String filePath) {
		def file = new File(filePath)
		if (!file.exists() || file.length() == 0) return null

		def randomAccess = new RandomAccessFile(file, "r")
		try {

			int shift = 1 // shift in case file ends with single newline
			for (long pos = file.length() - 1 - shift; pos >= 0; pos--) {
				randomAccess.seek(pos)
				if (randomAccess.read() == '\n') {
					return randomAccess.readLine()
				}
			}
			// assume that file has only one line
			randomAccess.seek(0)
			randomAccess.readLine()

		} finally {
			randomAccess.close()
		}
	}

	private static appendTo(String filePath, String text) {
		def file = new File(filePath)
		FileUtil.createParentDirs(file)
		file.append(text)
	}

	private static prependTo(String filePath, String text) {
		def tempFile = FileUtil.createTempFile("delta_flora", "_${new Random().nextInt(10000)}")
		def file = new File(filePath)

		tempFile.withOutputStream { output ->
			output.write(text.bytes)
			file.withInputStream { input ->
				// magic buffer size is copied from com.intellij.openapi.util.io.FileUtilRt#BUFFER (assume there is a reason for it)
				byte[] buffer = new byte[1024 * 20]
				while (true) {
					int read = input.read(buffer)
					if (read < 0) break
					output.write(buffer, 0, read)
				}
			}
		}

		file.delete()
		FileUtil.rename(tempFile, file)
	}
}


class ChangeExtractor {

	static Collection<ChangeEvent> fileChangeEventsFrom(CommittedChangeList changeList, Project project) {
		try {
			def commitInfo = commitInfoOf(changeList)
			changeList.changes.collect { Change change ->
				new ChangeEvent(commitInfo, fileChangeInfoOf(change, project, false), ElementChangeInfo.EMPTY)
			}
		} catch (ProcessCanceledException ignore) {
			[]
		}
	}

	static Collection<ChangeEvent> changeEventsFrom(CommittedChangeList changeList, Project project) {
		try {
			def commitInfo = commitInfoOf(changeList)
			changeList.changes.collectMany { Change change ->
				def fileChangeInfo = fileChangeInfoOf(change, project)
				withDefault([null], elementChangesOf(change, project)).collect{
					new ChangeEvent(commitInfo, fileChangeInfo, it)
				}
			} as Collection<ChangeEvent>
		} catch (ProcessCanceledException ignore) {
			[]
		}
	}

	private static CommitInfo commitInfoOf(CommittedChangeList changeList) {
		new CommitInfo(
			revisionNumberOf(changeList),
			removeEmailFrom(changeList.committerName),
			changeList.commitDate, changeList.comment.trim()
		)
	}

	private static FileChangeInfo fileChangeInfoOf(Change change, Project project, boolean countFileLines = true) {
		def nonEmptyRevision = nonEmptyRevisionOf(change)
		if (nonEmptyRevision.file.fileType.binary) countFileLines = false
		def (beforeText, afterText) = (countFileLines ? contentOf(change) : ["", ""])

		def packageBefore = measure("VCS content time"){ withDefault("", change.beforeRevision?.file?.parentPath?.path).replace(project.basePath, "") }
		def packageAfter = measure("VCS content time"){ withDefault("", change.afterRevision?.file?.parentPath?.path).replace(project.basePath, "") }

		new FileChangeInfo(
				nonEmptyRevision.file.name,
				change.type.toString(),
				packageBefore,
				packageAfter == packageBefore ? "" : packageAfter,
				beforeText.split("\n").length,
				afterText.split("\n").length
		)
	}

	private static Collection<ElementChangeInfo> elementChangesOf(Change change, Project project) {
		def nonEmptyRevision = nonEmptyRevisionOf(change)
		if (nonEmptyRevision.file.fileType.binary) return []
		def (beforeText, afterText) = contentOf(change)

		elementChangesBetween(beforeText, afterText) { String text ->
			runReadAction {
				def fileFactory = PsiFileFactory.getInstance(project)
				fileFactory.createFileFromText(nonEmptyRevision.file.name, nonEmptyRevision.file.fileType, text)
			} as PsiFile
		}
	}

	private static def nonEmptyRevisionOf(Change change) {
		change.afterRevision == null ? change.beforeRevision : change.afterRevision
	}

	private static def contentOf(Change change) {
		measure("VCS content time") {
			def beforeText = withDefault("", change.beforeRevision?.content)
			def afterText = withDefault("", change.afterRevision?.content)
			[beforeText, afterText]
		}
	}

	static Collection<ElementChangeInfo> elementChangesBetween(String beforeText, String afterText, Closure<PsiFile> psiParser) {
		PsiFile psiBefore = measure("parsing time"){ psiParser(beforeText) }
		PsiFile psiAfter = measure("parsing time"){ psiParser(afterText) }

		def changedFragments = measure("diff time") { new TextCompareProcessor(TRIM_SPACE).process(beforeText, afterText).findAll { it.type != null } }

		changedFragments.collectMany { LineFragment fragment ->
			measure("change events time") {
				def offsetToLineNumber = { int offset -> fragment.type == DELETED ? toLineNumber(offset, beforeText) : toLineNumber(offset, afterText) }

				List<ElementChangeInfo> elementChanges = []
				def addChangeEvent = { PsiNamedElement psiElement, int fromOffset, int toOffset ->
					def elementChange = new ElementChangeInfo(
							fullNameOf(psiElement),
							diffTypeOf(fragment),
							offsetToLineNumber(fromOffset),
							offsetToLineNumber(toOffset),
							fromOffset,
							toOffset
					)
					elementChanges << elementChange
				}

				def revisionWithCode = (fragment.type == DELETED ? psiBefore : psiAfter)
				def range = (fragment.type == DELETED ? fragment.getRange(SIDE1) : fragment.getRange(SIDE2))

				PsiNamedElement prevPsiElement = null
				int fromOffset = range.startOffset
				for (int offset = range.startOffset; offset < range.endOffset; offset++) {
					// running read action on fine-grained level because this seems to improve UI responsiveness
					// even though it will make the whole processing slower
					runReadAction {
						PsiNamedElement psiElement = methodOrClassAt(offset, revisionWithCode)
						if (psiElement != prevPsiElement) {
							if (prevPsiElement != null)
								addChangeEvent(prevPsiElement, fromOffset, offset)
							prevPsiElement = psiElement
							fromOffset = offset
						}
					}
				}
				runReadAction {
					if (prevPsiElement != null)
						addChangeEvent(prevPsiElement, fromOffset, range.endOffset)
				}

				elementChanges
			}
		} as Collection<ElementChangeInfo>
	}

	private static String revisionNumberOf(CommittedChangeList changeList) {
		// TODO this is a hack to get git ssh (it might be worth using VcsRevisionNumberAware but it's currently not released)
		if (changeList.class.simpleName == "GitCommittedChangeList") {
			changeList.name.with{ it[it.lastIndexOf('(') + 1..<it.lastIndexOf(')')] }
		} else {
			changeList.number.toString()
		}
	}

	private static String removeEmailFrom(String committerName) {
		committerName.replaceAll(/\s+<.+@.+>/, "").trim()
	}

	private static String diffTypeOf(LineFragment fragment) {
		// this is because if fragment has children it infers diff type from them,
		// which can be "INSERT/DELETED" event though from line point of view it is "CHANGED"
		def diffType = (fragment.childrenIterator != null ? CHANGED : fragment.type)

		switch (diffType) {
			case INSERT: return "added"
			case CHANGED: return "changed"
			case DELETED: return "deleted"
			case CONFLICT: return "other"
			case NONE: return "other"
			default: return "other"
		}
	}

	private static int toLineNumber(int offset, String text) {
		int counter = 0
		for (int i = 0; i < offset; i++) {
			if (text.charAt(i) == '\n') counter++
		}
		counter
	}

	private static String containingFileName(PsiElement psiElement) {
		if (psiElement == null) "null"
		else if (psiElement instanceof PsiFile) psiElement.name
		else (containingFileName(psiElement.parent))
	}

	private static String fullNameOf(PsiElement psiElement) {
		if (psiElement == null) "null"
		else if (psiElement instanceof PsiFile) ""
		else if (psiElement in PsiAnonymousClass) {
			def parentName = fullNameOf(psiElement.parent)
			def name = "[" + psiElement.baseClassType.className + "]"
			parentName.empty ? name : (parentName + "::" + name)
		} else if (psiElement instanceof PsiMethod || psiElement instanceof PsiClass) {
			def parentName = fullNameOf(psiElement.parent)
			parentName.empty ? psiElement.name : (parentName + "::" + psiElement.name)
		} else {
			fullNameOf(psiElement.parent)
		}
	}

	private static PsiNamedElement methodOrClassAt(int offset, PsiFile psiFile) {
		parentMethodOrClassOf(psiFile.findElementAt(offset))
	}

	private static PsiNamedElement parentMethodOrClassOf(PsiElement psiElement) {
		if (psiElement == null) null
		else if (psiElement instanceof PsiMethod) psiElement as PsiNamedElement
		else if (psiElement instanceof PsiClass) psiElement as PsiNamedElement
		else if (psiElement instanceof PsiFile) psiElement as PsiNamedElement
		else parentMethodOrClassOf(psiElement.parent)
	}

	private static <T> T withDefault(T defaultValue, T value) { value == null ? defaultValue : value }
}
