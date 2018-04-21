import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.DocumentChange
import com.google.cloud.firestore.ListenerRegistration
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import org.threeten.bp.Instant
import java.io.FileInputStream
import java.util.*


val registrations = ArrayList<ListenerRegistration>()
val subThreads = ArrayList<Thread>()
var builder: BuildTaxLedger? = null

fun main(args: Array<String>) {
    val listeningStart = Instant.now()
    Runtime.getRuntime().addShutdownHook(Thread {
        println("Exiting...")
        registrations.forEach { it.remove() }
        subThreads.forEach {
            it.interrupt()
        }
        builder?.interrupt()
    })
    val serviceAccount = FileInputStream("tax-ledger-firebase-adminsdk-vsllx-b1d31fecb2.json")

    val options = FirebaseOptions.Builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAccount))
            .build()

    val firebaseApp = FirebaseApp.initializeApp(options)
    val db = FirestoreClient.getFirestore(firebaseApp)


    db.collection("builds").addSnapshotListener { value, error ->
        error?.let {
            println("error: $error")
            return@addSnapshotListener
        }

        value?.let {
            value.documentChanges.forEach {
                val doc = it.document
                val createTime = doc.createTime
                if (createTime == null || createTime.isBefore(listeningStart))
                    return@forEach
                println("${it.type} ${it.document.id}")
                when (it.type) {
                    DocumentChange.Type.ADDED -> {
                        db.document("builds/${it.document.id}").set(mapOf(
                                "status" to "READY"))
                    }
                    DocumentChange.Type.REMOVED -> {
                        if (builder?.documentRef?.id == it.document.id)
                            builder?.interrupt()
                    }
                    DocumentChange.Type.MODIFIED -> {

                        when (it.document.data["status"].toString().toUpperCase()) {
                            "START" -> {
                                builder = BuildTaxLedger(db.document("builds/${it.document.id}"), db)
                                builder?.start()
                                db.document("builds/${it.document.id}").set(mapOf(
                                        "status" to "RUNNING"))
                            }
                            "STOP" -> {
                                if (builder?.documentRef?.id == it.document.id)
                                    builder?.interrupt()
                            }

                        }

                    }
                }

            }
        }
    }.also { registrations.add(it) }

    Scanner(System.`in`).next()
    System.exit(0)
}
