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

package net.micode.notes.ui;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.VersionColumns;
import net.micode.notes.model.NoteVersionManager;
import net.micode.notes.model.NoteVersionManager.NoteVersionData;

public class NoteVersionHistoryActivity extends ListActivity {
    public static final String EXTRA_NOTE_ID = "note_id";

    private static final int VERSION_COLUMN_ID = 0;
    private static final int VERSION_COLUMN_SNIPPET = 2;
    private static final int VERSION_COLUMN_CREATED_DATE = 4;

    private long mNoteId;
    private VersionAdapter mAdapter;
    private Cursor mCursor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.note_version_list);

        mNoteId = getIntent().getLongExtra(EXTRA_NOTE_ID, 0);
        if (mNoteId <= 0) {
            finish();
            return;
        }

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.menu_version_history);
        }

        mCursor = NoteVersionManager.getVersionsByNoteId(this, mNoteId);
        mAdapter = new VersionAdapter(this, mCursor);
        setListAdapter(mAdapter);

        if (mCursor == null || mCursor.getCount() == 0) {
            Toast.makeText(this, R.string.no_version_history, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCursor != null) {
            mCursor.close();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.note_version_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.menu_clear_all_versions) {
            showClearAllVersionsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        showVersionPreviewDialog(id);
    }

    private void showVersionPreviewDialog(final long versionId) {
        NoteVersionData versionData = NoteVersionManager.getVersionById(this, versionId);
        if (versionData == null) {
            return;
        }

        String dateStr = DateUtils.formatDateTime(this, versionData.createdDate,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NUMERIC_DATE
                        | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_YEAR);

        String message = getString(R.string.version_preview_message, dateStr, versionData.content);

        new AlertDialog.Builder(this)
                .setTitle(R.string.version_preview_title)
                .setMessage(message)
                .setPositiveButton(R.string.restore_version, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        restoreVersion(versionId);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void restoreVersion(long versionId) {
        NoteVersionData versionData = NoteVersionManager.getVersionById(this, versionId);
        if (versionData == null) {
            Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        ContentValues values = new ContentValues();
        values.put(DataColumns.CONTENT, versionData.content);
        values.put(DataColumns.MIME_TYPE, Notes.TextNote.CONTENT_ITEM_TYPE);

        int updated = getContentResolver().update(
                Notes.CONTENT_DATA_URI,
                values,
                DataColumns.NOTE_ID + "=? AND " + DataColumns.MIME_TYPE + "=?",
                new String[] { String.valueOf(mNoteId), Notes.TextNote.CONTENT_ITEM_TYPE });

        if (updated > 0) {
            ContentValues noteValues = new ContentValues();
            noteValues.put(Notes.NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
            noteValues.put(Notes.NoteColumns.BG_COLOR_ID, versionData.bgColorId);
            getContentResolver().update(
                    ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, mNoteId),
                    noteValues, null, null);

            Toast.makeText(this, R.string.restore_success, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } else {
            Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showClearAllVersionsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_all_versions_title)
                .setMessage(R.string.clear_all_versions_message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        clearAllVersions();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearAllVersions() {
        int deleted = NoteVersionManager.deleteVersionsByNoteId(this, mNoteId);
        if (deleted > 0) {
            Toast.makeText(this, getString(R.string.clear_versions_success, deleted), Toast.LENGTH_SHORT).show();
            mCursor = NoteVersionManager.getVersionsByNoteId(this, mNoteId);
            mAdapter.changeCursor(mCursor);
        }
    }

    private class VersionAdapter extends CursorAdapter {
        public VersionAdapter(Context context, Cursor c) {
            super(context, c, false);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            return LayoutInflater.from(context).inflate(R.layout.note_version_item, parent, false);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            TextView tvDate = (TextView) view.findViewById(R.id.tv_version_date);
            TextView tvSnippet = (TextView) view.findViewById(R.id.tv_version_snippet);

            long createdDate = cursor.getLong(VERSION_COLUMN_CREATED_DATE);
            String snippet = cursor.getString(VERSION_COLUMN_SNIPPET);

            String dateStr = DateUtils.formatDateTime(context, createdDate,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NUMERIC_DATE
                            | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_YEAR);

            tvDate.setText(dateStr);
            tvSnippet.setText(TextUtils.isEmpty(snippet) ? "" : snippet);
        }
    }
}
