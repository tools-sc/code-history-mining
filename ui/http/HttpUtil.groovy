package ui.http
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil

import static liveplugin.PluginUtil.changeGlobalVar

class HttpUtil {
	static String loadIntoHttpServer(String html, String projectName, String fileName) {
		def tempDir = FileUtil.createTempDirectory(projectName + "_", "")
		new File("$tempDir.absolutePath/$fileName").write(html)

		log_("Saved html file into: " + tempDir.absolutePath + "/" + fileName)

		def server = restartHttpServer(projectName, tempDir.absolutePath, {null}, {log_(it.toString())})
		"http://localhost:${server.port}/${fileName}"
	}

	private static SimpleHttpServer restartHttpServer(String id, String webRootPath, Closure handler = {null}, Closure errorListener = {}) {
		changeGlobalVar(id) { previousServer ->
			if (previousServer != null) {
				previousServer.stop()
			}

			def server = new SimpleHttpServer()
			def started = false
			for (port in (8100..10000)) {
				try {
					server.start(port, webRootPath, handler, errorListener)
					started = true
					break
				} catch (BindException ignore) {
				}
			}
			if (!started) throw new IllegalStateException("Failed to start server '${id}'")
			server
		}
	}

	private static log_(String message) { Logger.getInstance("CodeHistoryMining").info(message) }
}
