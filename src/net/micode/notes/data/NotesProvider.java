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

package net.micode.notes.data;


import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.R;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.VersionColumns;
import net.micode.notes.data.NotesDatabaseHelper.TABLE;


public class NotesProvider extends ContentProvider {
    private static final UriMatcher mMatcher;

    private NotesDatabaseHelper mHelper;

    private static final String TAG = "NotesProvider";

    private static final int URI_NOTE            = 1;
    private static final int URI_NOTE_ITEM       = 2;
    private static final int URI_DATA            = 3;
    private static final int URI_DATA_ITEM       = 4;

    private static final int URI_SEARCH          = 5;
    private static final int URI_SEARCH_SUGGEST  = 6;

    private static final int URI_VERSION         = 7;
    private static final int URI_VERSION_ITEM    = 8;

    static {
        mMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        mMatcher.addURI(Notes.AUTHORITY, "note", URI_NOTE);
        mMatcher.addURI(Notes.AUTHORITY, "note/#", URI_NOTE_ITEM);
        mMatcher.addURI(Notes.AUTHORITY, "data", URI_DATA);
        mMatcher.addURI(Notes.AUTHORITY, "data/#", URI_DATA_ITEM);
        mMatcher.addURI(Notes.AUTHORITY, "search", URI_SEARCH);
        mMatcher.addURI(Notes.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY, URI_SEARCH_SUGGEST);
        mMatcher.addURI(Notes.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*", URI_SEARCH_SUGGEST);
        mMatcher.addURI(Notes.AUTHORITY, "note_version", URI_VERSION);
        mMatcher.addURI(Notes.AUTHORITY, "note_version/#", URI_VERSION_ITEM);
    }

    /**
     * x'0A' represents the '\n' character in sqlite. For title and content in the search result,
     * we will trim '\n' and white space in order to show more information.
     */
    private static final String NOTES_SEARCH_PROJECTION = NoteColumns.ID + ","
        + NoteColumns.ID + " AS " + SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA + ","
        + "TRIM(REPLACE(" + NoteColumns.SNIPPET + ", x'0A','')) AS " + SearchManager.SUGGEST_COLUMN_TEXT_1 + ","
        + "TRIM(REPLACE(" + NoteColumns.SNIPPET + ", x'0A','')) AS " + SearchManager.SUGGEST_COLUMN_TEXT_2 + ","
        + R.drawable.search_result + " AS " + SearchManager.SUGGEST_COLUMN_ICON_1 + ","
        + "'" + Intent.ACTION_VIEW + "' AS " + SearchManager.SUGGEST_COLUMN_INTENT_ACTION + ","
        + "'" + Notes.TextNote.CONTENT_TYPE + "' AS " + SearchManager.SUGGEST_COLUMN_INTENT_DATA;

    private static String NOTES_SNIPPET_SEARCH_QUERY = "SELECT " + NOTES_SEARCH_PROJECTION
        + " FROM " + TABLE.NOTE
        + " WHERE " + NoteColumns.SNIPPET + " LIKE ?"
        + " AND " + NoteColumns.PARENT_ID + "<>" + Notes.ID_TRASH_FOLER
        + " AND " + NoteColumns.TYPE + "=" + Notes.TYPE_NOTE;

    @Override
    public boolean onCreate() {
        mHelper = NotesDatabaseHelper.getInstance(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        Cursor c = null;
        SQLiteDatabase db = mHelper.getReadableDatabase();
        String id = null;
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                c = db.query(TABLE.NOTE, projection, selection, selectionArgs, null, null,
                        sortOrder);
                break;
            case URI_NOTE_ITEM:
                id = uri.getPathSegments().get(1);
                String noteSelectionWithId = NoteColumns.ID + "=?" + parseSelection(selection);
                String[] noteSelectionArgsWithId = new String[] { id };
                if (selectionArgs != null) {
                    // 合并参数
                    String[] newSelectionArgs = new String[selectionArgs.length + 1];
                    newSelectionArgs[0] = id;
                    System.arraycopy(selectionArgs, 0, newSelectionArgs, 1, selectionArgs.length);
                    noteSelectionArgsWithId = newSelectionArgs;
                }
                c = db.query(TABLE.NOTE, projection, noteSelectionWithId, noteSelectionArgsWithId, null, null, sortOrder);
                break;
            case URI_DATA:
                c = db.query(TABLE.DATA, projection, selection, selectionArgs, null, null,
                        sortOrder);
                break;
            case URI_DATA_ITEM:
                id = uri.getPathSegments().get(1);
                String dataSelectionWithId = DataColumns.ID + "=?" + parseSelection(selection);
                String[] dataSelectionArgsWithId = new String[] { id };
                if (selectionArgs != null) {
                    // 合并参数
                    String[] newSelectionArgs = new String[selectionArgs.length + 1];
                    newSelectionArgs[0] = id;
                    System.arraycopy(selectionArgs, 0, newSelectionArgs, 1, selectionArgs.length);
                    dataSelectionArgsWithId = newSelectionArgs;
                }
                c = db.query(TABLE.DATA, projection, dataSelectionWithId, dataSelectionArgsWithId, null, null, sortOrder);
                break;
            case URI_SEARCH:
            case URI_SEARCH_SUGGEST:
                if (sortOrder != null || projection != null) {
                    throw new IllegalArgumentException(
                            "do not specify sortOrder, selection, selectionArgs, or projection" + "with this query");
                }

                String searchString = null;
                if (mMatcher.match(uri) == URI_SEARCH_SUGGEST) {
                    if (uri.getPathSegments().size() > 1) {
                        searchString = uri.getPathSegments().get(1);
                    }
                } else {
                    searchString = uri.getQueryParameter("pattern");
                }

                if (TextUtils.isEmpty(searchString)) {
                    return null;
                }

                try {
                    searchString = String.format("%%%s%%", searchString);
                    c = db.rawQuery(NOTES_SNIPPET_SEARCH_QUERY,
                            new String[] { searchString });
                } catch (IllegalStateException ex) {
                    Log.e(TAG, "got exception: " + ex.toString());
                }
                break;
            case URI_VERSION:
                c = db.query(TABLE.VERSION, projection, selection, selectionArgs, null, null,
                        sortOrder);
                break;
            case URI_VERSION_ITEM:
                id = uri.getPathSegments().get(1);
                String selectionWithId = VersionColumns.ID + "=?" + parseSelection(selection);
                String[] selectionArgsWithId = new String[] { id };
                if (selectionArgs != null) {
                    // 合并参数
                    String[] newSelectionArgs = new String[selectionArgs.length + 1];
                    newSelectionArgs[0] = id;
                    System.arraycopy(selectionArgs, 0, newSelectionArgs, 1, selectionArgs.length);
                    selectionArgsWithId = newSelectionArgs;
                }
                c = db.query(TABLE.VERSION, projection, selectionWithId, selectionArgsWithId, null, null, sortOrder);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (c != null) {
            c.setNotificationUri(getContext().getContentResolver(), uri);
        }
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        SQLiteDatabase db = mHelper.getWritableDatabase();
        long dataId = 0, noteId = 0, insertedId = 0;
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                insertedId = noteId = db.insert(TABLE.NOTE, null, values);
                break;
            case URI_DATA:
                if (values.containsKey(DataColumns.NOTE_ID)) {
                    noteId = values.getAsLong(DataColumns.NOTE_ID);
                } else {
                    Log.d(TAG, "Wrong data format without note id:" + values.toString());
                }
                insertedId = dataId = db.insert(TABLE.DATA, null, values);
                break;
            case URI_VERSION:
                insertedId = db.insert(TABLE.VERSION, null, values);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        // Notify the note uri
        if (noteId > 0) {
            getContext().getContentResolver().notifyChange(
                    ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId), null);
        }

        // Notify the data uri
        if (dataId > 0) {
            getContext().getContentResolver().notifyChange(
                    ContentUris.withAppendedId(Notes.CONTENT_DATA_URI, dataId), null);
        }

        return ContentUris.withAppendedId(uri, insertedId);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        int count = 0;
        String id = null;
        SQLiteDatabase db = mHelper.getWritableDatabase();
        boolean deleteData = false;
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                selection = "(" + selection + ") AND " + NoteColumns.ID + ">0 ";
                count = db.delete(TABLE.NOTE, selection, selectionArgs);
                break;
            case URI_NOTE_ITEM:
                id = uri.getPathSegments().get(1);
                /**
                 * ID that smaller than 0 is system folder which is not allowed to
                 * trash
                 */
                long noteId = Long.valueOf(id);
                if (noteId <= 0) {
                    break;
                }
                String noteDeleteSelectionWithId = NoteColumns.ID + "=?" + parseSelection(selection);
                String[] noteDeleteSelectionArgsWithId = new String[] { id };
                if (selectionArgs != null) {
                    // 合并参数
                    String[] newSelectionArgs = new String[selectionArgs.length + 1];
                    newSelectionArgs[0] = id;
                    System.arraycopy(selectionArgs, 0, newSelectionArgs, 1, selectionArgs.length);
                    noteDeleteSelectionArgsWithId = newSelectionArgs;
                }
                count = db.delete(TABLE.NOTE, noteDeleteSelectionWithId, noteDeleteSelectionArgsWithId);
                break;
            case URI_DATA:
                count = db.delete(TABLE.DATA, selection, selectionArgs);
                deleteData = true;
                break;
            case URI_DATA_ITEM:
                id = uri.getPathSegments().get(1);
                String dataDeleteSelectionWithId = DataColumns.ID + "=?" + parseSelection(selection);
                String[] dataDeleteSelectionArgsWithId = new String[] { id };
                if (selectionArgs != null) {
                    // 合并参数
                    String[] newSelectionArgs = new String[selectionArgs.length + 1];
                    newSelectionArgs[0] = id;
                    System.arraycopy(selectionArgs, 0, newSelectionArgs, 1, selectionArgs.length);
                    dataDeleteSelectionArgsWithId = newSelectionArgs;
                }
                count = db.delete(TABLE.DATA, dataDeleteSelectionWithId, dataDeleteSelectionArgsWithId);
                deleteData = true;
                break;
            case URI_VERSION:
                count = db.delete(TABLE.VERSION, selection, selectionArgs);
                break;
            case URI_VERSION_ITEM:
                id = uri.getPathSegments().get(1);
                String versionDeleteSelectionWithId = VersionColumns.ID + "=?" + parseSelection(selection);
                String[] versionDeleteSelectionArgsWithId = new String[] { id };
                if (selectionArgs != null) {
                    // 合并参数
                    String[] newSelectionArgs = new String[selectionArgs.length + 1];
                    newSelectionArgs[0] = id;
                    System.arraycopy(selectionArgs, 0, newSelectionArgs, 1, selectionArgs.length);
                    versionDeleteSelectionArgsWithId = newSelectionArgs;
                }
                count = db.delete(TABLE.VERSION, versionDeleteSelectionWithId, versionDeleteSelectionArgsWithId);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (count > 0) {
            if (deleteData) {
                getContext().getContentResolver().notifyChange(Notes.CONTENT_NOTE_URI, null);
            }
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        int count = 0;
        String id = null;
        SQLiteDatabase db = mHelper.getWritableDatabase();
        boolean updateData = false;
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                increaseNoteVersion(-1, selection, selectionArgs);
                count = db.update(TABLE.NOTE, values, selection, selectionArgs);
                break;
            case URI_NOTE_ITEM:
                id = uri.getPathSegments().get(1);
                increaseNoteVersion(Long.valueOf(id), selection, selectionArgs);
                String noteUpdateSelectionWithId = NoteColumns.ID + "=?" + parseSelection(selection);
                String[] noteUpdateSelectionArgsWithId = new String[] { id };
                if (selectionArgs != null) {
                    // 合并参数
                    String[] newSelectionArgs = new String[selectionArgs.length + 1];
                    newSelectionArgs[0] = id;
                    System.arraycopy(selectionArgs, 0, newSelectionArgs, 1, selectionArgs.length);
                    noteUpdateSelectionArgsWithId = newSelectionArgs;
                }
                count = db.update(TABLE.NOTE, values, noteUpdateSelectionWithId, noteUpdateSelectionArgsWithId);
                break;
            case URI_DATA:
                count = db.update(TABLE.DATA, values, selection, selectionArgs);
                updateData = true;
                break;
            case URI_DATA_ITEM:
                id = uri.getPathSegments().get(1);
                String dataUpdateSelectionWithId = DataColumns.ID + "=?" + parseSelection(selection);
                String[] dataUpdateSelectionArgsWithId = new String[] { id };
                if (selectionArgs != null) {
                    // 合并参数
                    String[] newSelectionArgs = new String[selectionArgs.length + 1];
                    newSelectionArgs[0] = id;
                    System.arraycopy(selectionArgs, 0, newSelectionArgs, 1, selectionArgs.length);
                    dataUpdateSelectionArgsWithId = newSelectionArgs;
                }
                count = db.update(TABLE.DATA, values, dataUpdateSelectionWithId, dataUpdateSelectionArgsWithId);
                updateData = true;
                break;
            case URI_VERSION:
                count = db.update(TABLE.VERSION, values, selection, selectionArgs);
                break;
            case URI_VERSION_ITEM:
                id = uri.getPathSegments().get(1);
                String versionUpdateSelectionWithId = VersionColumns.ID + "=?" + parseSelection(selection);
                String[] versionUpdateSelectionArgsWithId = new String[] { id };
                if (selectionArgs != null) {
                    // 合并参数
                    String[] newSelectionArgs = new String[selectionArgs.length + 1];
                    newSelectionArgs[0] = id;
                    System.arraycopy(selectionArgs, 0, newSelectionArgs, 1, selectionArgs.length);
                    versionUpdateSelectionArgsWithId = newSelectionArgs;
                }
                count = db.update(TABLE.VERSION, values, versionUpdateSelectionWithId, versionUpdateSelectionArgsWithId);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        if (count > 0) {
            if (updateData) {
                getContext().getContentResolver().notifyChange(Notes.CONTENT_NOTE_URI, null);
            }
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    private String parseSelection(String selection) {
        return (!TextUtils.isEmpty(selection) ? " AND (" + selection + ')' : "");
    }

    private void increaseNoteVersion(long id, String selection, String[] selectionArgs) {
        StringBuilder sql = new StringBuilder(120);
        sql.append("UPDATE ");
        sql.append(TABLE.NOTE);
        sql.append(" SET ");
        sql.append(NoteColumns.VERSION);
        sql.append("=" + NoteColumns.VERSION + "+1 ");

        if (id > 0 || !TextUtils.isEmpty(selection)) {
            sql.append(" WHERE ");
        }
        if (id > 0) {
            sql.append(NoteColumns.ID + "=" + String.valueOf(id));
        }
        if (!TextUtils.isEmpty(selection)) {
            String selectString = id > 0 ? parseSelection(selection) : selection;
            for (String args : selectionArgs) {
                selectString = selectString.replaceFirst("\\?", args);
            }
            sql.append(selectString);
        }

        mHelper.getWritableDatabase().execSQL(sql.toString());
    }

    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }

}
