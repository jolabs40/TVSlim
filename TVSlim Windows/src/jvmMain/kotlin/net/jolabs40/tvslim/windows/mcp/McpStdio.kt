package net.jolabs40.tvslim.windows.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves [server] over the stdio transport until [input] closes, which is how the client asks the server to stop.
 *
 * Requests run concurrently, since reading a TV takes seconds and the client may ask for something else meanwhile;
 * answers are written whole, one per line, in the order they finish.
 */
suspend fun serveStdio(server: McpServer, input: InputStream, output: OutputStream) = coroutineScope {
    val reader = input.bufferedReader(Charsets.UTF_8)
    val writer = output.bufferedWriter(Charsets.UTF_8)
    val writing = Mutex()
    val running = ConcurrentHashMap<JsonElement, Job>()

    while (true) {
        val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
        if (line.isBlank()) continue

        val cancelled = server.cancelledRequest(line)
        if (cancelled != null) {
            running.remove(cancelled)?.cancel()
            continue
        }

        val id = server.requestId(line)
        val job = launch {
            val answer = server.handle(line) ?: return@launch
            writing.withLock {
                withContext(Dispatchers.IO) {
                    writer.write(answer)
                    writer.write("\n")
                    writer.flush()
                }
            }
        }
        if (id != null) {
            running[id] = job
            job.invokeOnCompletion { running.remove(id, job) }
        }
    }
    coroutineContext.cancelChildren()
}
