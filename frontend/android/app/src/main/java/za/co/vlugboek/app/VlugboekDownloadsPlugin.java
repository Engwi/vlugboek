package za.co.vlugboek.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

@CapacitorPlugin(name = "VlugboekDownloads")
public class VlugboekDownloadsPlugin extends Plugin {
    @PluginMethod
    public void saveAndOpen(PluginCall call) {
        String filename = safeFilename(call.getString("filename", "vlugboek-download"));
        String mimeType = call.getString("mimeType", "application/octet-stream");
        String base64 = call.getString("base64", "");
        boolean open = call.getBoolean("open", true);

        if (base64.isBlank()) {
            call.reject("Downloaded file is empty");
            return;
        }

        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            Uri uri = saveToDownloads(filename, mimeType, bytes);
            if (open) {
                openFile(uri, mimeType, filename);
            }

            JSObject result = new JSObject();
            result.put("uri", uri.toString());
            call.resolve(result);
        } catch (Exception ex) {
            call.reject("Could not save downloaded file", ex);
        }
    }

    private Uri saveToDownloads(String filename, String mimeType, byte[] bytes) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Vlugboek");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            ContentResolver resolver = getContext().getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return saveToCache(filename, bytes);
            }

            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) {
                    throw new IOException("Could not open download output stream");
                }
                output.write(bytes);
            }

            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        }

        return saveToCache(filename, bytes);
    }

    private Uri saveToCache(String filename, byte[] bytes) throws IOException {
        File directory = new File(getContext().getCacheDir(), "downloads");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create download cache directory");
        }

        File file = new File(directory, filename);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }

        return FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
    }

    private void openFile(Uri uri, String mimeType, String filename) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(uri, mimeType);
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            activity.startActivity(Intent.createChooser(viewIntent, "Open " + filename));
        } catch (ActivityNotFoundException ex) {
            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType(mimeType);
            sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
            sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(sendIntent, "Share " + filename));
        }
    }

    private String safeFilename(String filename) {
        String safe = filename == null ? "" : filename.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "-").trim();
        if (safe.isBlank()) {
            return "vlugboek-download";
        }
        return safe;
    }
}
