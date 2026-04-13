/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.model;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.VersionColumns;

public class NoteVersionManager {
    private static final String TAG = "NoteVersionManager";

    public static final String[] VERSION_PROJECTION = new String[] {
            VersionColumns.ID,
            VersionColumns.NOTE_ID,
            VersionColumns.VERSION_SNIPPET,
            VersionColumns.VERSION_CONTENT,
            VersionColumns.VERSION_CREATED_DATE,
            VersionColumns.VERSION_BG_COLOR_ID
    };

    private static final int ID_COLUMN = 0;
    private static final int NOTE_ID_COLUMN = 1;
    private static final int SNIPPET_COLUMN = 2;
    private static final int CONTENT_COLUMN = 3;
    private static final int CREATED_DATE_COLUMN = 4;
    private static final int BG_COLOR_ID_COLUMN = 5;

    public static long saveVersion(Context context, long noteId, String snippet, String content, int bgColorId) {
        if (noteId <= 0 || TextUtils.isEmpty(content)) {
            return -1;
        }

        ContentValues values = new ContentValues();
        values.put(VersionColumns.NOTE_ID, noteId);
        values.put(VersionColumns.VERSION_SNIPPET, snippet != null ? snippet : "");
        values.put(VersionColumns.VERSION_CONTENT, content);
        values.put(VersionColumns.VERSION_CREATED_DATE, System.currentTimeMillis());
        values.put(VersionColumns.VERSION_BG_COLOR_ID, bgColorId);

        Uri uri = context.getContentResolver().insert(Notes.NoteVersion.CONTENT_URI, values);
        if (uri != null) {
            long versionId = ContentUris.parseId(uri);
            Log.d(TAG, "Saved version " + versionId + " for note " + noteId);
            return versionId;
        }
        return -1;
    }

    public static Cursor getVersionsByNoteId(Context context, long noteId) {
        if (noteId <= 0) {
            return null;
        }

        return context.getContentResolver().query(
                Notes.NoteVersion.CONTENT_URI,
                VERSION_PROJECTION,
                VersionColumns.NOTE_ID + "=?",
                new String[] { String.valueOf(noteId) },
                VersionColumns.VERSION_CREATED_DATE + " DESC");
    }

    public static NoteVersionData getVersionById(Context context, long versionId) {
        if (versionId <= 0) {
            return null;
        }

        Cursor cursor = context.getContentResolver().query(
                ContentUris.withAppendedId(Notes.NoteVersion.CONTENT_URI, versionId),
                VERSION_PROJECTION,
                null,
                null,
                null);

        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    NoteVersionData data = new NoteVersionData();
                    data.id = cursor.getLong(ID_COLUMN);
                    data.noteId = cursor.getLong(NOTE_ID_COLUMN);
                    data.snippet = cursor.getString(SNIPPET_COLUMN);
                    data.content = cursor.getString(CONTENT_COLUMN);
                    data.createdDate = cursor.getLong(CREATED_DATE_COLUMN);
                    data.bgColorId = cursor.getInt(BG_COLOR_ID_COLUMN);
                    return data;
                }
            } finally {
                cursor.close();
            }
        }
        return null;
    }

    public static int deleteVersionsByNoteId(Context context, long noteId) {
        if (noteId <= 0) {
            return 0;
        }

        return context.getContentResolver().delete(
                Notes.NoteVersion.CONTENT_URI,
                VersionColumns.NOTE_ID + "=?",
                new String[] { String.valueOf(noteId) });
    }

    public static int getVersionCount(Context context, long noteId) {
        if (noteId <= 0) {
            return 0;
        }

        Cursor cursor = context.getContentResolver().query(
                Notes.NoteVersion.CONTENT_URI,
                new String[] { "count(*)" },
                VersionColumns.NOTE_ID + "=?",
                new String[] { String.valueOf(noteId) },
                null);

        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    return cursor.getInt(0);
                }
            } finally {
                cursor.close();
            }
        }
        return 0;
    }

    public static class NoteVersionData {
        public long id;
        public long noteId;
        public String snippet;
        public String content;
        public long createdDate;
        public int bgColorId;
    }
}
