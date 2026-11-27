package com.saimum.callmanager.recording

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Copies a finished recording into a user-chosen Storage Access Framework
 * folder, if one is configured.
 *
 * Deliberately a copy, not the primary write target: rewriting the audio
 * capture pipeline (WavFileWriter/CallAudioRecorder) to stream directly
 * into a SAF URI would mean giving up random-access seeking (needed to
 * patch the WAV header with the final size after recording) — SAF output
 * streams aren't seekable the way a plain File is. Recording to app-private
 * storage first and copying afterward keeps the capture path simple and
 * reliable, at the cost of briefly using double the disk space per
 * recording. The app-private copy remains the source of truth for the
 * in-app library (playback, delete) regardless of whether this copy
 * succeeds.
 */
class RecordingFolderCopier(private val context: Context) {

    /** Never throws — a failed copy (revoked permission, folder deleted, etc.) is silently skipped. */
    fun copyIfConfigured(sourceFile: File, customFolderUriString: String?) {
        val uriString = customFolderUriString ?: return
        runCatching {
            val treeUri = Uri.parse(uriString)
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return
            val newDoc = treeDoc.createFile("audio/wav", sourceFile.name) ?: return
            context.contentResolver.openOutputStream(newDoc.uri)?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            }
        }
    }
}
