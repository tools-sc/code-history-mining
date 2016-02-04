package codehistoryminer.plugin.vcsaccess.implementation.wrappers

import codehistoryminer.core.common.langutil.Date
import com.intellij.openapi.project.Project as IJProject
import com.intellij.openapi.vcs.VcsRoot as IJVcsRoot
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList as IJCommit
import codehistoryminer.plugin.vcsaccess.VcsActionsLog
import codehistoryminer.plugin.vcsaccess.implementation.IJCommitReader
import com.intellij.vcs.log.VcsShortCommitDetails
import vcsreader.Change
import vcsreader.Commit
import vcsreader.VcsProject
import vcsreader.VcsRoot

import static codehistoryminer.core.common.langutil.Misc.withDefault
import static vcsreader.Change.noRevision

class VcsRootWrapper implements VcsRoot {
    private final IJProject project
    private final IJVcsRoot vcsRoot
    private final String commonVcsRoot
    private final VcsActionsLog log

    VcsRootWrapper(IJProject project, IJVcsRoot vcsRoot, String commonVcsRoot, VcsActionsLog log) {
        this.project = project
        this.vcsRoot = vcsRoot
        this.commonVcsRoot = commonVcsRoot
        this.log = log
    }

    @Override VcsProject.LogResult log(java.util.Date fromDate, java.util.Date toDate) {
        def reader = new IJCommitReader(project, log)
        def commits = reader.readCommits(new Date(fromDate), new Date(toDate), [vcsRoot])

        def result = []
        for (IJCommit ijCommit in commits) {
            def revision = withDefault(noRevision, ijCommit.changes.first().afterRevision?.revisionNumber?.asString())
            def revisionBefore = withDefault(noRevision, ijCommit.changes.first().beforeRevision?.revisionNumber?.asString())

	        // workaround because hg4idea will use "revision:changeset" as id (using terms of hg)
	        if (ijCommit?.vcs?.name == "hg4idea") {
		        revision = keepHgChangeSetOnly(revision)
		        revisionBefore = keepHgChangeSetOnly(revision)
	        }

            def changes = wrapChangesFrom(ijCommit)
            if (changes.empty) continue

	        def commitTime
	        if (ijCommit instanceof VcsShortCommitDetails) {
		        commitTime = new java.util.Date(ijCommit.authorTime)
	        } else {
		        commitTime = ijCommit.commitDate
	        }
	        def commit = new Commit(
                    revision,
                    revisionBefore,
			        commitTime,
                    ijCommit.committerName,
                    ijCommit.comment.trim(),
                    changes
            )

            result.add(commit)
        }

        new VcsProject.LogResult(result, [])
    }

	private List<Change> wrapChangesFrom(IJCommit ijCommit) {
        ijCommit.changes
            .collect { ChangeWrapper.create(it, commonVcsRoot) }
            .findAll{ it != ChangeWrapper.none }
    }

	private static String keepHgChangeSetOnly(String s) {
		def i = s.indexOf(":")
		if (i == -1 || i == s.length() - 1) return s
		s.substring(i + 1)
	}

    @Override VcsProject.LogFileContentResult logFileContent(String filePath, String revision) {
        throw new IllegalStateException("Should never be called (filePath: ${filePath}; revision: ${revision}")
    }

    @Override VcsProject.UpdateResult update() {
        throw new UnsupportedOperationException()
    }

    @Override VcsProject.CloneResult cloneToLocal() {
        throw new UnsupportedOperationException()
    }
}
