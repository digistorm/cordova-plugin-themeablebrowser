package com.initialxy.cordova.themeablebrowser;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import org.apache.cordova.CordovaWebView;
import org.json.JSONException;

//Download Files imports
import android.app.DownloadManager;
import android.os.Environment;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.CookieManager;
import android.widget.Toast;
import android.webkit.MimeTypeMap;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import java.io.File;
import android.support.v4.content.FileProvider;

import static android.content.Context.DOWNLOAD_SERVICE;

//Permissions
import android.content.pm.PackageManager;

public class InAppBrowserDownloads implements DownloadListener{

    ThemeableBrowser plugin;

    String url;
    String userAgent;
    String contentDisposition;
    String mimetype;
    long contentLength;

    public InAppBrowserDownloads(ThemeableBrowser plugin) {
        this.plugin = plugin;
    }


    public void onDownloadStart(String url, String userAgent,
                                String contentDisposition, String mimetype,
                                long contentLength) {

        InAppBrowserDownloads.this.url = url;
        InAppBrowserDownloads.this.userAgent = userAgent;
        InAppBrowserDownloads.this.contentDisposition = contentDisposition;
        InAppBrowserDownloads.this.mimetype = mimetype;
        InAppBrowserDownloads.this.contentLength = contentLength;


        if (Build.VERSION.SDK_INT >= 23) {
            if (plugin.cordova.getActivity().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                processDownload();
            } else {
                plugin.cordova.requestPermission(InAppBrowserDownloads.this.plugin, 0, android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        } else { //permission is automatically granted on sdk<23 upon installation
            processDownload();
        }
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions,
         int[] grantResults) throws JSONException
    {
        for(int r:grantResults)
        {
            if(r == PackageManager.PERMISSION_DENIED)
            {
                Toast.makeText(plugin.cordova.getActivity().getApplicationContext(), "Error downloading file, missing storage permissions", Toast.LENGTH_LONG).show();
            } else {
                InAppBrowserDownloads.this.processDownload();
            }
        }
    }

    protected void processDownload() {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(InAppBrowserDownloads.this.url));
        plugin.cordova.getActivity().registerReceiver(attachmentDownloadCompleteReceive, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        try {
            request.setMimeType(InAppBrowserDownloads.this.mimetype);
            String cookies = CookieManager.getInstance().getCookie(InAppBrowserDownloads.this.url);
            request.addRequestHeader("Cookie", cookies);
            request.addRequestHeader("User-Agent", InAppBrowserDownloads.this.userAgent);
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); // Notify client once download is completed!
            final String filename = URLUtil.guessFileName(InAppBrowserDownloads.this.url, InAppBrowserDownloads.this.contentDisposition, InAppBrowserDownloads.this.mimetype);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            DownloadManager dm = (DownloadManager) plugin.cordova.getActivity().getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(request);

            Toast.makeText(plugin.cordova.getActivity().getApplicationContext(), "Downloading File '" + filename + "'", Toast.LENGTH_LONG).show();
        } catch (Exception exception) {
            Toast.makeText(plugin.cordova.getActivity().getApplicationContext(), "Error downloading file, missing storage permissions", Toast.LENGTH_LONG).show();
            exception.printStackTrace();
        }
    }

    /**
    * Used to get MimeType from url.
    *
    * @param url Url.
    * @return Mime Type for the given url.
    */
    private String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            MimeTypeMap mime = MimeTypeMap.getSingleton();
            type = mime.getMimeTypeFromExtension(extension);
        }
        return type;
    }

    /**
    * Attachment download complete receiver.
    * <p/>
    * 1. Receiver gets called once attachment download completed.
    * 2. Open the downloaded file.
    */
    BroadcastReceiver attachmentDownloadCompleteReceive = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0);
                openDownloadedAttachment(context, downloadId);
            }
        }
    };

    /**
    * Used to open the downloaded attachment.
    *
    * @param context    Content.
    * @param downloadId Id of the downloaded file to open.
    */
    private void openDownloadedAttachment(final Context context, final long downloadId) {
        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadId);
        Cursor cursor = downloadManager.query(query);
        if (cursor.moveToFirst()) {
            int downloadStatus = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
            String downloadLocalUri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
            String downloadMimeType = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE));
            if ((downloadStatus == DownloadManager.STATUS_SUCCESSFUL) && downloadLocalUri != null) {
                openDownloadedAttachment(context, Uri.parse(downloadLocalUri), downloadMimeType);
            }
        }
        cursor.close();
    }

    /**
    * Used to open the downloaded attachment.
    * <p/>
    * 1. Fire intent to open download file using external application.
    *
    * 2. Note:
    * 2.a. We can't share fileUri directly to other application (because we will get FileUriExposedException from Android7.0).
    * 2.b. Hence we can only share content uri with other application.
    * 2.c. We must have declared FileProvider in manifest.
    * 2.c. Refer - https://developer.android.com/reference/android/support/v4/content/FileProvider.html
    *
    * @param context            Context.
    * @param attachmentUri      Uri of the downloaded attachment to be opened.
    * @param attachmentMimeType MimeType of the downloaded attachment.
    */
    private void openDownloadedAttachment(final Context context, Uri attachmentUri, final String attachmentMimeType) {
        if(attachmentUri!=null) {
            // Get Content Uri.
            if (ContentResolver.SCHEME_FILE.equals(attachmentUri.getScheme())) {
                // FileUri - Convert it to contentUri.
                File file = new File(attachmentUri.getPath());
                attachmentUri = FileProvider.getUriForFile(plugin.cordova.getActivity(), cordova.getActivity().getPackageName(), file);
            }

            Intent openAttachmentIntent = new Intent(Intent.ACTION_VIEW);
            openAttachmentIntent.setDataAndType(attachmentUri, attachmentMimeType);
            openAttachmentIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                context.startActivity(openAttachmentIntent);
            } catch (Exception e) {
                Toast.makeText(context, context.getString(R.string.unable_to_open_file), Toast.LENGTH_LONG).show();
            }
        }
    }
}
