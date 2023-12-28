package com.example.dedrone

import android.content.ContentValues.TAG
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed class Result<out R> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Error(val exception: Exception) : Result<Nothing>()
}

class LogcatHelper(
    context: Context,
    private val maxFileSize: Long,
    private val maxFolderSize: Long
) {

    class LogcatBuilder() {
        private var maxFileSize: Long = MAX_FILE_SIZE
        private var maxFolderSize: Long = MAX_FOLDER_SIZE

        fun setMaxFileSize(fileSize: Long): LogcatBuilder {
            maxFileSize = fileSize
            return this
        }

        fun setMaxFolderSize(folderSize: Long): LogcatBuilder {
            maxFolderSize = folderSize
            return this
        }

        fun build(context: Context): LogcatHelper {
            if (maxFileSize > maxFolderSize) {
                throw IllegalStateException("maxFileSize must be less than maxFolderSize")
            }
            return LogcatHelper(context, maxFileSize, maxFolderSize)
        }
    }


    private var logDumper: LogDumper? = null
    private val pID: Int
    private var publicAppDirectory = ""
    private var logcatPath = ""

    companion object {
        const val MAX_FILE_SIZE = 2097152L // 2MB
        const val MAX_FOLDER_SIZE = 10485760L // 10MB
        private const val TAG = "CameraX-MLKit"
    }

    init {
        init(context)
        pID = android.os.Process.myPid()
        Log.d(TAG, "initLogging")
        start()
    }

    private fun init(context: Context) {
        context.getExternalFilesDir(null)?.let {
            publicAppDirectory = it.absolutePath
            logcatPath = publicAppDirectory + File.separator + "logs"
            val logDirectory = File(logcatPath)
            if (!logDirectory.exists()) {
                logDirectory.mkdir()
            }
        }
    }

    fun start() {
        logDumper ?: run {
            logDumper = LogDumper(pID.toString(), logcatPath)
        }
        logDumper?.let { logDumper ->
            if (!logDumper.isAlive) logDumper.start()
        }
    }

    fun stop() {
        logDumper?.stopLogs()
        logDumper = null
    }

    private fun mergeLogs(sourceDir: String, outputFile: File) {
        val logcatDir = File(sourceDir)

        if (!outputFile.exists()) outputFile.createNewFile()
        val pw = PrintWriter(outputFile)
        val logFiles = logcatDir.listFiles()

        logFiles.sortBy { it.lastModified() }

        logFiles.forEach { logFile ->
            val br = BufferedReader(FileReader(logFile))

            var line: String? = null
            while ({ line = br.readLine(); line }() != null) {
                pw.println(line)
            }
        }
        pw.flush()
        pw.close()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun mergeLogsApi26(sourceDir: String, outputFile: File) {
        val outputFilePath = Paths.get(outputFile.absolutePath)
        val logcatPath = Paths.get(sourceDir)

        Files.list(logcatPath)
            .sorted { o1, o2 ->
                Files.getLastModifiedTime(o1).compareTo(Files.getLastModifiedTime(o2))
            }
            .flatMap(Files::lines)
            .forEach { line ->
                Files.write(
                    outputFilePath,
                    (line + System.lineSeparator()).toByteArray(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                )
            }
    }

    suspend fun getLogFile(): Result<File> {
        stop()
        return withContext(Dispatchers.IO) {
            try {
                val outputDir = File(publicAppDirectory + File.separator + "output")
                val outputFile = File(outputDir.absolutePath + File.separator + "logs.txt")

                if (!outputDir.exists()) outputDir.mkdir()
                if (outputFile.exists()) outputFile.delete()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mergeLogsApi26(logcatPath, outputFile)
                } else {
                    mergeLogs(logcatPath, outputFile)
                }
                Result.Success(outputFile)
            } catch (e: Exception) {
                Result.Error(e)
            } finally {
                start()
            }
        }
    }

    private inner class LogDumper constructor(
        private val pID: String,
        private val logcatPath: String
    ) : Thread() {
        private var logcatProc: Process? = null
        private var reader: BufferedReader? = null
        private var mRunning = true
        private var command = ""
        private var clearLogCommand = ""
        private var outputStream: FileOutputStream? = null

        init {
            try {
                Log.d(TAG, "start log file creating")
                outputStream = FileOutputStream(createLogFile(logcatPath))
                Log.d(TAG, "log file created")
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            }
            Log.d(TAG, "init logging")
            command = "logcat | grep \"($pID)\""
            clearLogCommand = "logcat -c"
        }

        internal fun stopLogs() {
            mRunning = false
        }

        override fun run() {
            if (outputStream == null) return
            try {
                Log.d(TAG, "logging running")
                Runtime.getRuntime().exec(clearLogCommand)
                logcatProc = Runtime.getRuntime().exec(command)
                reader = BufferedReader(InputStreamReader(logcatProc!!.inputStream), 1024)
                var line: String? = null

                while (mRunning && run {
                        line = reader!!.readLine()
                        line
                    } != null) {
                    if (!mRunning) {
                        break
                    }
                    if (line!!.isEmpty()) {
                        continue
                    }

                    if (outputStream!!.channel.size() >= maxFileSize) {
                        outputStream!!.close()
                        outputStream = FileOutputStream(createLogFile(logcatPath))
                    }
                    if (getFolderSize(logcatPath) >= maxFolderSize) {
                        deleteOldestFile(logcatPath)
                    }
                    outputStream!!.write((line + System.lineSeparator()).toByteArray())
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                logcatProc?.destroy()
                logcatProc = null

                try {
                    reader?.close()
                    outputStream?.close()
                    reader = null
                    outputStream = null
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        private fun getFolderSize(path: String): Long {
            File(path).run {
                var size = 0L
                if (this.isDirectory && this.listFiles() != null) {
                    for (file in this.listFiles()!!) {
                        size += getFolderSize(file.absolutePath)
                    }
                } else {
                    size = this.length()
                }
                return size
            }
        }

        private fun formattedTimestamp(): String {
            val currentTimestamp: LocalDateTime = LocalDateTime.now()

            // Define a format for the timestamp
            val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

            // Format the timestamp using the defined format
            val formattedTimestamp: String = currentTimestamp.format(formatter)

            return formattedTimestamp
        }





//        private fun createLogFile(dir: String) =
//            File(dir, "logcat_" + System.currentTimeMillis() + ".txt")

        private fun createLogFile(dir: String) =
            File(dir, "logcat_" + formattedTimestamp() + ".txt")

//        private fun createLogFile(dir: String) {
//            Log.d(TAG, "formattedTimestamp: $formattedTimestamp")
//            Log.d(TAG, "log file created with the name: $formattedTimestamp.txt")
//            File(dir, "$formattedTimestamp.txt")
//        }


        private fun deleteOldestFile(path: String) {
            val directory = File(path)
            if (directory.isDirectory) {
                directory.listFiles()?.toMutableList()?.run {
                    this.sortBy { it.lastModified() }
                    this.first().delete()
                    Log.d(TAG, "log file deleted")
                }
            }
        }
    }
}