
import com.google.cloud.firestore.DocumentReference
import com.google.cloud.firestore.Firestore
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.*

class BuildTaxLedger
(val documentRef: DocumentReference, val db : Firestore) : Thread() {
    private lateinit var process: Process
    private var outputPrinter: Thread? = null

    override fun run() {
        runCmd("RemoveReleases","rm","-r", "release")
        runCmd("GitReset","/usr/local/bin/git", "reset", "--hard", "origin/master")
        runCmd("GitPull","/usr/local/bin/git", "pull")
        val exitCode = runCmd("package-all","./package-all.sh", documentRef.id)

        if (exitCode == 0)
            documentRef.update(mapOf("status" to "DONE", "currentTask" to null))
        else
            documentRef.update(mapOf("status" to "FAILED", "currentTask" to null))
        println("DONE")
    }

    private fun runCmd(taskName: String, vararg commands: String) : Int {

        val gitBuilder = ProcessBuilder(*commands)
        gitBuilder.directory(File("/Users/admin1/GoogleDrive/JSProjects/taxledger-gui"))
        gitBuilder.redirectErrorStream(true)
        process = gitBuilder.start()
        documentRef.update(mapOf("currentTask" to commands.joinToString(" ")))
        outputPrinter = Thread {
            try {
                val isr = InputStreamReader(process.inputStream)
                val br = BufferedReader(isr)
                var line: String? = br.readLine()
                while (line != null) {
                    println("$taskName> $line")
                    val laneLocal : String = line
                    db.runTransaction {
                        val snap = it.get(documentRef).get()
                        var logs = arrayListOf<String>()
                        if(snap.data!![taskName] != null)
                            if((snap.data!![taskName] as Map<String,Any>)["logs"] as ArrayList<String>? != null)
                            logs = (snap.data!![taskName] as Map<String,Any>)["logs"] as ArrayList<String>
                        logs.add(laneLocal)
                        it.update(documentRef,"$taskName.logs",logs.takeLast(5))
                    }
                    line = br.readLine()
                }
            } catch (ioe: IOException) {
                ioe.printStackTrace()
            }
        }
        outputPrinter?.start()
        val exitCode = process.waitFor()
        documentRef.update(mapOf("$taskName.exitCode" to exitCode))
        outputPrinter?.interrupt()
        return exitCode
    }

    override fun interrupt() {
        super.interrupt()
        documentRef.update(mapOf("status" to "INTERRUPTED"))
        outputPrinter?.interrupt()
        process.destroyForcibly()

    }
}