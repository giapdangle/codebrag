package com.softwaremill.codebrag.service.commits.jgit

import com.google.common.io.Files
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.lib.{ObjectId, Repository}
import com.softwaremill.codebrag.common.Utils
import java.io.{PrintWriter, File}
import org.eclipse.jgit.api.Git
import com.softwaremill.codebrag.repository.GitRepository
import com.softwaremill.codebrag.repository.config.RepoData
import org.eclipse.jgit.api.ListBranchCommand.ListMode

class TemporaryGitRepo(val tempDir: File, repo: Repository) {
  private val git = new Git(repo)

  def repository = {
    val repoData = RepoData(tempDir.getAbsolutePath, tempDir.getName, "git", None)
    new GitRepository(repoData) {
      override val BranchListMode = ListMode.ALL
    }
  }

  /**
   * @return SHA of the commit
   */
  def createCommit(commitMessage: String, fileNamesAndContents: (String, String)*): String = {
    fileNamesAndContents.foreach { case (fileName, content) =>
      val file = new File(tempDir, fileName)
      setFileContent(file, content)
      git.add().addFilepattern(fileName).call()
    }

    ObjectId.toString(git.commit().setMessage(commitMessage).call().getId)
  }

  def createCommits(count: Int): List[String] = {
    val shas = for(i <- 1 to count) yield {
      createCommit(s"commit_${i}", (s"file_${i}.txt", s"file_${i}_content"))
    }
    shas.toList
  }

  def checkoutBranch(branchName: String, create: Boolean = true) {
    git.checkout().setName(branchName).setCreateBranch(create).call()
  }

  private def setFileContent(file: File, content: String) {
    val p = new PrintWriter(file)
    try {
      p.println(content)
    } finally {
      p.close()
    }
  }
}

object TemporaryGitRepo {
  def withGitRepo[T](block: TemporaryGitRepo => T) = {
    val tempDir = Files.createTempDir()

    try {
      val repo = new FileRepositoryBuilder().setWorkTree(tempDir).build()
      repo.create(false)

      block(new TemporaryGitRepo(tempDir, repo))
    } finally {
      Utils.rmMinusRf(tempDir)
    }
  }
}
