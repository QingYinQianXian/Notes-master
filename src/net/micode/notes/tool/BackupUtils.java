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

/*
 * 版权声明：代码来源于 MiCode 开源社区，遵循 Apache 2.0 协议
 * 这意味着您可以自由使用、修改和分发这段代码
 */

package net.micode.notes.tool; // 声明代码包路径

// 导入必要的 Android 和 Java 类库
import android.content.Context; // 用于访问应用资源和数据库
import android.database.Cursor; // 用于操作数据库查询结果集
import android.os.Environment; // 用于检查外部存储（SD卡）状态
import android.text.TextUtils; // 字符串工具类，检查空字符串
import android.text.format.DateFormat; // 格式化日期时间
import android.util.Log; // 打印日志

import net.micode.notes.R; // 资源文件，包含字符串、格式等
import net.micode.notes.data.Notes; // 笔记数据库定义
import net.micode.notes.data.Notes.DataColumns; // 笔记数据表的列名常量
import net.micode.notes.data.Notes.DataConstants; // 数据类型常量
import net.micode.notes.data.Notes.NoteColumns; // 笔记表的列名常量

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;

/**
 * 备份工具类：负责将笔记数据导出为文本文件到SD卡
 * 使用单例模式，确保全局只有一个实例
 */
public class BackupUtils {
    private static final String TAG = "BackupUtils"; // 日志标签

    // 单例实例
    private static BackupUtils sInstance;

    /**
     * 获取单例实例（线程安全）
     * @param context 应用上下文，用于访问资源和数据库
     * @return BackupUtils 单例对象
     */
    public static synchronized BackupUtils getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new BackupUtils(context);
        }
        return sInstance;
    }

    // ==================== 备份/恢复状态码 ====================
    public static final int STATE_SD_CARD_UNMOUONTED = 0;    // SD卡未挂载，无法读写
    public static final int STATE_BACKUP_FILE_NOT_EXIST = 1; // 备份文件不存在
    public static final int STATE_DATA_DESTROIED = 2;        // 数据格式损坏，可能被其他程序修改
    public static final int STATE_SYSTEM_ERROR = 3;          // 系统运行时错误导致失败
    public static final int STATE_SUCCESS = 4;               // 操作成功

    // 文本导出器实例
    private TextExport mTextExport;

    /**
     * 私有构造方法，初始化文本导出器
     */
    private BackupUtils(Context context) {
        mTextExport = new TextExport(context);
    }

    /**
     * 检查外部存储（SD卡）是否可用
     * @return true 如果SD卡已挂载且可读写
     */
    private static boolean externalStorageAvailable() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    /**
     * 执行导出操作
     * @return 状态码，表示导出结果
     */
    public int exportToText() {
        return mTextExport.exportToText();
    }

    /**
     * 获取导出的文件名
     */
    public String getExportedTextFileName() {
        return mTextExport.mFileName;
    }

    /**
     * 获取导出的文件目录
     */
    public String getExportedTextFileDir() {
        return mTextExport.mFileDirectory;
    }

    // ==================== 文本导出内部类 ====================
    /**
     * 文本格式导出器：将数据库中的笔记转换为可读的文本格式
     */
    private static class TextExport {
        // 查询笔记时需要获取的列：ID、修改时间、摘要、类型
        private static final String[] NOTE_PROJECTION = {
                NoteColumns.ID,              // 笔记ID
                NoteColumns.MODIFIED_DATE,   // 最后修改时间
                NoteColumns.SNIPPET,         // 笔记摘要/文件夹名
                NoteColumns.TYPE             // 类型：文件夹或笔记
        };

        // 列的索引位置，提高访问效率
        private static final int NOTE_COLUMN_ID = 0;
        private static final int NOTE_COLUMN_MODIFIED_DATE = 1;
        private static final int NOTE_COLUMN_SNIPPET = 2;

        // 查询笔记内容时需要获取的列
        private static final String[] DATA_PROJECTION = {
                DataColumns.CONTENT,    // 文本内容或附件路径
                DataColumns.MIME_TYPE,  // 数据类型（文本、通话记录等）
                DataColumns.DATA1,      // 扩展数据1
                DataColumns.DATA2,      // 扩展数据2
                DataColumns.DATA3,      // 扩展数据3
                DataColumns.DATA4,      // 扩展数据4（通话记录中存电话号码）
        };

        // 数据列的索引
        private static final int DATA_COLUMN_CONTENT = 0;
        private static final int DATA_COLUMN_MIME_TYPE = 1;
        private static final int DATA_COLUMN_CALL_DATE = 2;    // 通话时间（存在DATA1）
        private static final int DATA_COLUMN_PHONE_NUMBER = 4; // 电话号码（存在DATA4）

        // 导出格式模板数组，从资源文件读取
        private final String[] TEXT_FORMAT;
        private static final int FORMAT_FOLDER_NAME = 0;   // 文件夹名格式
        private static final int FORMAT_NOTE_DATE = 1;     // 笔记日期格式
        private static final int FORMAT_NOTE_CONTENT = 2;  // 笔记内容格式

        private Context mContext;      // 应用上下文
        private String mFileName;      // 导出的文件名
        private String mFileDirectory; // 导出的文件目录

        /**
         * 构造方法：初始化格式模板
         */
        public TextExport(Context context) {
            // 从资源文件读取格式模板（支持多语言）
            TEXT_FORMAT = context.getResources().getStringArray(R.array.format_for_exported_note);
            mContext = context;
            mFileName = "";
            mFileDirectory = "";
        }

        /**
         * 获取指定ID的格式字符串
         */
        private String getFormat(int id) {
            return TEXT_FORMAT[id];
        }

        /**
         * 导出指定文件夹下的所有笔记
         * @param folderId 文件夹ID
         * @param ps 输出流，用于写入文本
         */
        private void exportFolderToText(String folderId, PrintStream ps) {
            // 查询该文件夹下的所有笔记
            Cursor notesCursor = mContext.getContentResolver().query(
                    Notes.CONTENT_NOTE_URI,           // 笔记内容URI
                    NOTE_PROJECTION,                   // 需要的列
                    NoteColumns.PARENT_ID + "=?",      // 条件：父文件夹ID等于指定值
                    new String[]{folderId},            // 条件参数
                    null);

            if (notesCursor != null) {
                if (notesCursor.moveToFirst()) {
                    do {
                        // 1. 先打印笔记的最后修改时间
                        ps.println(String.format(
                                getFormat(FORMAT_NOTE_DATE),
                                DateFormat.format(
                                        mContext.getString(R.string.format_datetime_mdhm),
                                        notesCursor.getLong(NOTE_COLUMN_MODIFIED_DATE))));
                        
                        // 2. 再导出笔记的具体内容
                        String noteId = notesCursor.getString(NOTE_COLUMN_ID);
                        exportNoteToText(noteId, ps);
                    } while (notesCursor.moveToNext());
                }
                notesCursor.close(); // 关闭游标释放资源
            }
        }

        /**
         * 导出一条笔记的内容到输出流
         * @param noteId 笔记ID
         * @param ps 输出流
         */
        private void exportNoteToText(String noteId, PrintStream ps) {
            // 查询该笔记的所有数据内容
            Cursor dataCursor = mContext.getContentResolver().query(
                    Notes.CONTENT_DATA_URI,
                    DATA_PROJECTION,
                    DataColumns.NOTE_ID + "=?",
                    new String[]{noteId},
                    null);

            if (dataCursor != null) {
                if (dataCursor.moveToFirst()) {
                    do {
                        String mimeType = dataCursor.getString(DATA_COLUMN_MIME_TYPE);
                        
                        // 处理通话记录类型的笔记
                        if (DataConstants.CALL_NOTE.equals(mimeType)) {
                            // 导出电话号码
                            String phoneNumber = dataCursor.getString(DATA_COLUMN_PHONE_NUMBER);
                            long callDate = dataCursor.getLong(DATA_COLUMN_CALL_DATE);
                            String location = dataCursor.getString(DATA_COLUMN_CONTENT);

                            if (!TextUtils.isEmpty(phoneNumber)) {
                                ps.println(String.format(getFormat(FORMAT_NOTE_CONTENT), phoneNumber));
                            }
                            // 导出通话时间
                            ps.println(String.format(
                                    getFormat(FORMAT_NOTE_CONTENT),
                                    DateFormat.format(mContext.getString(R.string.format_datetime_mdhm), callDate)));
                            // 导出录音文件位置
                            if (!TextUtils.isEmpty(location)) {
                                ps.println(String.format(getFormat(FORMAT_NOTE_CONTENT), location));
                            }
                        } 
                        // 处理普通文本笔记
                        else if (DataConstants.NOTE.equals(mimeType)) {
                            String content = dataCursor.getString(DATA_COLUMN_CONTENT);
                            if (!TextUtils.isEmpty(content)) {
                                ps.println(String.format(getFormat(FORMAT_NOTE_CONTENT), content));
                            }
                        }
                    } while (dataCursor.moveToNext());
                }
                dataCursor.close();
            }
            
            // 每条笔记之间添加分隔符，便于阅读
            try {
                ps.write(new byte[]{Character.LINE_SEPARATOR, Character.LETTER_NUMBER});
            } catch (IOException e) {
                Log.e(TAG, e.toString());
            }
        }

        /**
         * 核心导出方法：将整个数据库的笔记导出为文本文件
         * @return 状态码
         */
        public int exportToText() {
            // 1. 检查SD卡是否可用
            if (!externalStorageAvailable()) {
                Log.d(TAG, "Media was not mounted");
                return STATE_SD_CARD_UNMOUONTED;
            }

            // 2. 获取输出流
            PrintStream ps = getExportToTextPrintStream();
            if (ps == null) {
                Log.e(TAG, "get print stream error");
                return STATE_SYSTEM_ERROR;
            }

            // 3. 导出所有文件夹及其笔记
            // 查询条件：类型为文件夹 且 不是回收站文件夹，或者是通话记录文件夹
            Cursor folderCursor = mContext.getContentResolver().query(
                    Notes.CONTENT_NOTE_URI,
                    NOTE_PROJECTION,
                    "(" + NoteColumns.TYPE + "=" + Notes.TYPE_FOLDER + " AND "
                            + NoteColumns.PARENT_ID + "<>" + Notes.ID_TRASH_FOLER + ") OR "
                            + NoteColumns.ID + "=" + Notes.ID_CALL_RECORD_FOLDER,
                    null, null);

            if (folderCursor != null) {
                if (folderCursor.moveToFirst()) {
                    do {
                        // 获取文件夹名称
                        String folderName = "";
                        if (folderCursor.getLong(NOTE_COLUMN_ID) == Notes.ID_CALL_RECORD_FOLDER) {
                            // 通话记录文件夹使用特殊名称
                            folderName = mContext.getString(R.string.call_record_folder_name);
                        } else {
                            folderName = folderCursor.getString(NOTE_COLUMN_SNIPPET);
                        }
                        
                        // 打印文件夹标题
                        if (!TextUtils.isEmpty(folderName)) {
                            ps.println(String.format(getFormat(FORMAT_FOLDER_NAME), folderName));
                        }
                        
                        // 导出该文件夹下的所有笔记
                        String folderId = folderCursor.getString(NOTE_COLUMN_ID);
                        exportFolderToText(folderId, ps);
                    } while (folderCursor.moveToNext());
                }
                folderCursor.close();
            }

            // 4. 导出根目录下未分类的笔记（父文件夹ID为0）
            Cursor noteCursor = mContext.getContentResolver().query(
                    Notes.CONTENT_NOTE_URI,
                    NOTE_PROJECTION,
                    NoteColumns.TYPE + "=" + Notes.TYPE_NOTE + " AND " + NoteColumns.PARENT_ID + "=0",
                    null, null);

            if (noteCursor != null) {
                if (noteCursor.moveToFirst()) {
                    do {
                        // 打印修改时间
                        ps.println(String.format(
                                getFormat(FORMAT_NOTE_DATE),
                                DateFormat.format(
                                        mContext.getString(R.string.format_datetime_mdhm),
                                        noteCursor.getLong(NOTE_COLUMN_MODIFIED_DATE))));
                        // 导出笔记内容
                        String noteId = noteCursor.getString(NOTE_COLUMN_ID);
                        exportNoteToText(noteId, ps);
                    } while (noteCursor.moveToNext());
                }
                noteCursor.close();
            }
            
            ps.close(); // 关闭输出流
            return STATE_SUCCESS;
        }

        /**
         * 获取用于导出文本的打印流
         * 创建以日期命名的文本文件
         */
        private PrintStream getExportToTextPrintStream() {
            // 创建文件：路径从资源文件读取，文件名包含当前日期
            File file = generateFileMountedOnSDcard(
                    mContext,
                    R.string.file_path,              // 目录路径
                    R.string.file_name_txt_format);  // 文件名格式
                    
            if (file == null) {
                Log.e(TAG, "create file to exported failed");
                return null;
            }
            
            // 保存文件名和目录供外部查询
            mFileName = file.getName();
            mFileDirectory = mContext.getString(R.string.file_path);
            
            // 创建打印流
            PrintStream ps = null;
            try {
                FileOutputStream fos = new FileOutputStream(file);
                ps = new PrintStream(fos);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return null;
            } catch (NullPointerException e) {
                e.printStackTrace();
                return null;
            }
            return ps;
        }
    }

    // ==================== 工具方法 ====================
    /**
     * 在SD卡上生成文件
     * @param context 应用上下文
     * @param filePathResId 文件路径资源ID
     * @param fileNameFormatResId 文件名格式资源ID
     * @return 创建的文件对象，失败返回null
     */
    private static File generateFileMountedOnSDcard(Context context, int filePathResId, int fileNameFormatResId) {
        // 构建完整文件路径
        StringBuilder sb = new StringBuilder();
        sb.append(Environment.getExternalStorageDirectory()); // SD卡根目录
        sb.append(context.getString(filePathResId));          // 应用子目录
        File filedir = new File(sb.toString());
        
        // 添加文件名（包含当前日期，避免重名）
        sb.append(context.getString(
                fileNameFormatResId,
                DateFormat.format(context.getString(R.string.format_date_ymd), System.currentTimeMillis())));
        File file = new File(sb.toString());

        try {
            // 确保目录存在
            if (!filedir.exists()) {
                filedir.mkdir();
            }
            // 创建新文件
            if (!file.exists()) {
                file.createNewFile();
            }
            return file;
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }
}


