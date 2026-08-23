package com.autoedit.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Version-aware, permission-free image picker.
 *
 * WHY NOT ActivityResultContracts.PickMultipleVisualMedia(maxItems):
 * On Android 13+ its createIntent() throws IllegalArgumentException whenever
 * maxItems > MediaStore.getPickImagesMaxLimit() (system default: 100).
 * Any maxItems above the device limit crashes the app on tap. We build the
 * intents ourselves with a safe, clamped limit and a full fallback chain:
 *
 *   API 33+  -> system Photo Picker (ACTION_PICK_IMAGES), no permission
 *   API 29+  -> SAF ACTION_OPEN_DOCUMENT (persistent read grant), no permission
 *   API 26+  -> SAF ACTION_GET_CONTENT, no permission
 *
 * No storage permission is ever requested.
 */
object ImagePicker {

    private const val ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES"
    private const val EXTRA_PICK_IMAGES_MAX = "android.provider.extra.PICK_IMAGES_MAX"

    /** How many images a single pick may contain (clamped to the device limit). */
    const val MAX_SELECT = 500

    /** True when the system Photo Picker can handle ACTION_PICK_IMAGES on this device. */
    fun photoPickerAvailable(ctx: Context): Boolean = try {
        Build.VERSION.SDK_INT >= 33 &&
            ctx.packageManager.queryIntentActivities(Intent(ACTION_PICK_IMAGES), 0).isNotEmpty()
    } catch (e: Throwable) {
        false
    }

    /**
     * Primary pick intent for this device.
     * The picker's max is clamped to the device's own limit, so it can never
     * throw "Max items must be less or equals MediaStore.getPickImagesMaxLimit()".
     */
    fun createIntent(ctx: Context): Intent {
        if (Build.VERSION.SDK_INT >= 33) {
            val intent = Intent(ACTION_PICK_IMAGES)
            val limit = try {
                MediaStore.getPickImagesMaxLimit().coerceAtLeast(1)
            } catch (e: Throwable) {
                100
            }
            val max = MAX_SELECT.coerceAtMost(limit)
            if (max >= 2) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                intent.putExtra(EXTRA_PICK_IMAGES_MAX, max)
            }
            return intent
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // SAF: stable URI + persistent read permission, no permission prompt
            return Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        // API 26-28: classic SAF content pick
        return Intent(Intent.ACTION_GET_CONTENT)
            .setType("image/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addCategory(Intent.CATEGORY_OPENABLE)
    }

    /** Last-resort fallback if the primary intent cannot be resolved on this ROM. */
    fun fallbackIntent(ctx: Context): Intent =
        Intent(Intent.ACTION_GET_CONTENT)
            .setType("image/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addCategory(Intent.CATEGORY_OPENABLE)

    /** Extract selected image URIs from a picker result. Canceled => empty list. */
    fun parseUris(resultCode: Int, data: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || data == null) return emptyList()
        val out = ArrayList<Uri>()
        data.data?.let { out.add(it) }
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { out.add(it) }
            }
        }
        return out.distinct()
    }

    /**
     * Persist read access where the grant is persistable (OPEN_DOCUMENT).
     * Photo-picker (media/picks) and GET_CONTENT URIs are ignored gracefully.
     */
    fun persistReadPermissions(ctx: Context, uris: List<Uri>) {
        for (u in uris) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Throwable) {
                // not a persistable grant - the URI is still usable while the app has it
            }
        }
    }
}
