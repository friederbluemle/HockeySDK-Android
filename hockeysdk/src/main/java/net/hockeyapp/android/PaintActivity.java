package net.hockeyapp.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.Toast;

import net.hockeyapp.android.utils.AsyncTaskUtils;
import net.hockeyapp.android.utils.HockeyLog;
import net.hockeyapp.android.utils.ImageUtils;
import net.hockeyapp.android.utils.Util;
import net.hockeyapp.android.views.PaintView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class PaintActivity extends Activity {

    /**
     * URI of the image to annotate
     */
    public static final String EXTRA_IMAGE_URI = "imageUri";

    private static final int MENU_SAVE_ID = Menu.FIRST;
    private static final int MENU_UNDO_ID = Menu.FIRST + 1;
    private static final int MENU_CLEAR_ID = Menu.FIRST + 2;

    private PaintView mPaintView;
    private Uri mImageUri;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* Get image path. */
        Bundle extras = getIntent().getExtras();
        if (extras == null || extras.getParcelable(EXTRA_IMAGE_URI) == null) {
            HockeyLog.error("Can't set up PaintActivity as image extra was not provided!");
            return;
        }
        mImageUri = extras.getParcelable(EXTRA_IMAGE_URI);
        determineOrientation();
    }

    @SuppressLint("StaticFieldLeak")
    private void determineOrientation() {
        AsyncTaskUtils.execute(new AsyncTask<Void, Object, Integer>() {
            @Override
            protected Integer doInBackground(Void... voids) {
                return ImageUtils.determineOrientation(PaintActivity.this, mImageUri);
            }

            @Override
            protected void onPostExecute(Integer desiredOrientation) {
                setRequestedOrientation(desiredOrientation);

                int displayWidth = getResources().getDisplayMetrics().widthPixels;
                int displayHeight = getResources().getDisplayMetrics().heightPixels;
                int currentOrientation = displayWidth > displayHeight ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE :
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                if (currentOrientation != desiredOrientation) {
                    /* Activity will be destroyed again.. skip the following expensive operations. */
                    HockeyLog.debug("Image loading skipped because activity will be destroyed for orientation change.");
                    return;
                }

                showPaintView();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        menu.add(0, MENU_SAVE_ID, 0, getString(R.string.hockeyapp_paint_menu_save));
        menu.add(0, MENU_UNDO_ID, 0, getString(R.string.hockeyapp_paint_menu_undo));
        menu.add(0, MENU_CLEAR_ID, 0, getString(R.string.hockeyapp_paint_menu_clear));

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SAVE_ID:
                makeResult();
                return true;

            case MENU_UNDO_ID:
                mPaintView.undo();
                return true;

            case MENU_CLEAR_ID:
                mPaintView.clearImage();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!mPaintView.isClear()) {
                DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case DialogInterface.BUTTON_POSITIVE:
                                makeResult();
                                break;

                            case DialogInterface.BUTTON_NEGATIVE:
                                PaintActivity.this.finish();
                                break;

                            case DialogInterface.BUTTON_NEUTRAL:
                                /* No action. */
                                break;
                        }
                    }
                };

                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage(R.string.hockeyapp_paint_dialog_message)
                        .setPositiveButton(R.string.hockeyapp_paint_dialog_positive_button, dialogClickListener)
                        .setNegativeButton(R.string.hockeyapp_paint_dialog_negative_button, dialogClickListener)
                        .setNeutralButton(R.string.hockeyapp_paint_dialog_neutral_button, dialogClickListener)
                        .show();
                return true;
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    private void showPaintView() {
        int displayWidth = getResources().getDisplayMetrics().widthPixels;
        int displayHeight = getResources().getDisplayMetrics().heightPixels;

        /* Create view and find out which orientation is needed. */
        mPaintView = new PaintView(this, mImageUri, displayWidth, displayHeight);

        LinearLayout vLayout = new LinearLayout(this);
        LinearLayout.LayoutParams vParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        vLayout.setLayoutParams(vParams);
        vLayout.setGravity(Gravity.CENTER);
        vLayout.setOrientation(LinearLayout.VERTICAL);

        LinearLayout hLayout = new LinearLayout(this);
        LinearLayout.LayoutParams hParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        hLayout.setLayoutParams(hParams);
        hLayout.setGravity(Gravity.CENTER);
        hLayout.setOrientation(LinearLayout.HORIZONTAL);

        vLayout.addView(hLayout);
        hLayout.addView(mPaintView);
        setContentView(vLayout);

        Toast toast = Toast.makeText(this, R.string.hockeyapp_paint_indicator_toast, Toast.LENGTH_LONG);
        toast.show();
    }

    @SuppressLint("StaticFieldLeak")
    private void makeResult() {
        mPaintView.setDrawingCacheEnabled(true);
        final Bitmap bitmap = mPaintView.getDrawingCache();
        AsyncTaskUtils.execute(new AsyncTask<Void, Object, Boolean>() {
            File result;

            @Override
            protected Boolean doInBackground(Void... args) {
                File hockeyAppCache = new File(getCacheDir(), Constants.FILES_DIRECTORY_NAME);
                if (!hockeyAppCache.exists() && !hockeyAppCache.mkdir()) {
                    return false;
                }
                String imageName = Util.getFileName(PaintActivity.this, mImageUri);
                imageName = imageName.substring(0, imageName.lastIndexOf('.'));
                String filename = imageName + ".jpg";
                result = new File(hockeyAppCache, filename);
                int suffix = 1;
                while (result.exists()) {
                    result = new File(hockeyAppCache, imageName + "_" + suffix + ".jpg");
                    suffix++;
                }
                try {
                    FileOutputStream out = new FileOutputStream(result);
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                    out.close();
                } catch (IOException e) {
                    HockeyLog.error("Could not save image.", e);
                    return false;
                }
                return true;
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (success) {
                    Intent intent = new Intent();
                    Uri uri = Uri.fromFile(result);
                    intent.putExtra(EXTRA_IMAGE_URI, uri);
                    if (getParent() == null) {
                        setResult(Activity.RESULT_OK, intent);
                    } else {
                        getParent().setResult(Activity.RESULT_OK, intent);
                    }
                } else {
                    if (getParent() == null) {
                        setResult(Activity.RESULT_CANCELED);
                    } else {
                        getParent().setResult(Activity.RESULT_CANCELED);
                    }
                }
                finish();
            }
        });
    }
}
