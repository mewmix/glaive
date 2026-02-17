package com.mewmix.glaive.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONObject

class ToolProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val columns = arrayOf("name", "description", "parameters")
        val cursor = MatrixCursor(columns)

        // list_files
        cursor.addRow(arrayOf(
            "list_files",
            "List files in a directory.",
            JSONObject().put("path", "string").toString()
        ))

        // read_file
        cursor.addRow(arrayOf(
            "read_file",
            "Read the content of a file (Max 1MB).",
            JSONObject().put("path", "string").toString()
        ))

        // write_file
        cursor.addRow(arrayOf(
            "write_file",
            "Write content to a file.",
            JSONObject().put("path", "string").put("content", "string").toString()
        ))

        // create_dir
        cursor.addRow(arrayOf(
            "create_dir",
            "Create a directory.",
            JSONObject().put("path", "string").toString()
        ))

        // delete_file
        cursor.addRow(arrayOf(
            "delete_file",
            "Delete a file or directory.",
            JSONObject().put("path", "string").toString()
        ))

        // search_files
        cursor.addRow(arrayOf(
            "search_files",
            "Search for files.",
            JSONObject().put("query", "string").put("root_path", "string").toString()
        ))

        return cursor
    }

    override fun getType(uri: Uri): String? {
        return "vnd.android.cursor.dir/vnd.com.mewmix.glaive.tools"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        return 0
    }
}
