package com.topjohnwu.magisk;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.CardView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.topjohnwu.magisk.asyncs.CheckUpdates;
import com.topjohnwu.magisk.components.AlertDialogBuilder;
import com.topjohnwu.magisk.components.ExpandableView;
import com.topjohnwu.magisk.components.Fragment;
import com.topjohnwu.magisk.components.SnackbarMaker;
import com.topjohnwu.magisk.receivers.DownloadReceiver;
import com.topjohnwu.magisk.receivers.ManagerUpdate;
import com.topjohnwu.magisk.utils.Shell;
import com.topjohnwu.magisk.utils.Topic;
import com.topjohnwu.magisk.utils.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import butterknife.BindColor;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.Unbinder;

public class MagiskFragment extends Fragment
        implements Topic.Subscriber, SwipeRefreshLayout.OnRefreshListener, ExpandableView {

    private static final String UNINSTALLER = "magisk_uninstaller.sh";
    private static final String UTIL_FUNCTIONS= "util_functions.sh";
    private static final int SELECT_BOOT_IMG = 3;

    private Container expandableContainer = new Container();

    private MagiskManager mm;
    private Unbinder unbinder;
    private static boolean shownDialog = false;

    @BindView(R.id.swipeRefreshLayout) SwipeRefreshLayout mSwipeRefreshLayout;

    @BindView(R.id.magisk_update_card) CardView magiskUpdateCard;
    @BindView(R.id.magisk_update_icon) ImageView magiskUpdateIcon;
    @BindView(R.id.magisk_update_status) TextView magiskUpdateText;
    @BindView(R.id.magisk_update_progress) ProgressBar magiskUpdateProgress;

    @BindView(R.id.magisk_status_icon) ImageView magiskStatusIcon;
    @BindView(R.id.magisk_version) TextView magiskVersionText;
    @BindView(R.id.root_status_icon) ImageView rootStatusIcon;
    @BindView(R.id.root_status) TextView rootStatusText;

    @BindView(R.id.safetyNet_card) CardView safetyNetCard;
    @BindView(R.id.safetyNet_refresh) ImageView safetyNetRefreshIcon;
    @BindView(R.id.safetyNet_status) TextView safetyNetStatusText;
    @BindView(R.id.safetyNet_check_progress) ProgressBar safetyNetProgress;
    @BindView(R.id.expand_layout) LinearLayout expandLayout;
    @BindView(R.id.cts_status_icon) ImageView ctsStatusIcon;
    @BindView(R.id.cts_status) TextView ctsStatusText;
    @BindView(R.id.basic_status_icon) ImageView basicStatusIcon;
    @BindView(R.id.basic_status) TextView basicStatusText;

    @BindView(R.id.bootimage_card) CardView bootImageCard;
    @BindView(R.id.block_spinner) Spinner spinner;
    @BindView(R.id.detect_bootimage) Button detectButton;
    @BindView(R.id.install_option_card) CardView installOptionCard;
    @BindView(R.id.keep_force_enc) CheckBox keepEncChkbox;
    @BindView(R.id.keep_verity) CheckBox keepVerityChkbox;
    @BindView(R.id.install_button) CardView installButton;
    @BindView(R.id.install_text) TextView installText;
    @BindView(R.id.uninstall_button) CardView uninstallButton;

    @BindColor(R.color.red500) int colorBad;
    @BindColor(R.color.green500) int colorOK;
    @BindColor(R.color.yellow500) int colorWarn;
    @BindColor(R.color.grey500) int colorNeutral;
    @BindColor(R.color.blue500) int colorInfo;

    @OnClick(R.id.safetyNet_title)
    public void safetyNet() {
        safetyNetProgress.setVisibility(View.VISIBLE);
        safetyNetRefreshIcon.setVisibility(View.GONE);
        safetyNetStatusText.setText(R.string.checking_safetyNet_status);
        Utils.checkSafetyNet(getActivity());
        collapse();
    }

    @OnClick(R.id.install_button)
    public void install() {
        shownDialog = true;

        // Show Manager update first
        if (mm.remoteManagerVersionCode > BuildConfig.VERSION_CODE) {
            new AlertDialogBuilder(getActivity())
                    .setTitle(getString(R.string.repo_install_title, getString(R.string.app_name)))
                    .setMessage(getString(R.string.repo_install_msg,
                            Utils.getLegalFilename("MagiskManager-v" +
                            mm.remoteManagerVersionString + ".apk")))
                    .setCancelable(true)
                    .setPositiveButton(R.string.install, (d, i) -> {
                        Intent intent = new Intent(mm, ManagerUpdate.class);
                        intent.putExtra(MagiskManager.INTENT_LINK, mm.managerLink);
                        intent.putExtra(MagiskManager.INTENT_VERSION, mm.remoteManagerVersionString);
                        getActivity().sendBroadcast(intent);
                    })
                    .setNegativeButton(R.string.no_thanks, null)
                    .show();
            return;
        }

        String bootImage = null;
        if (Shell.rootAccess()) {
            if (mm.bootBlock != null) {
                bootImage = mm.bootBlock;
            } else {
                int idx = spinner.getSelectedItemPosition();
                if (idx > 0)  {
                    bootImage = mm.blockList.get(idx - 1);
                } else {
                    SnackbarMaker.make(getActivity(),
                            R.string.manual_boot_image, Snackbar.LENGTH_LONG).show();
                    return;
                }
            }
        }
        final String boot = bootImage;
        ((NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE)).cancelAll();
        String filename = "Magisk-v" + mm.remoteMagiskVersionString + ".zip";
        new AlertDialogBuilder(getActivity())
            .setTitle(getString(R.string.repo_install_title, getString(R.string.magisk)))
            .setMessage(getString(R.string.repo_install_msg, filename))
            .setCancelable(true)
            .setPositiveButton(
                R.string.install,
                (d, i) -> {
                    List<String> options = new ArrayList<>();
                    options.add(getString(R.string.download_zip_only));
                    options.add(getString(R.string.patch_boot_file));
                    if (Shell.rootAccess()) {
                        options.add(getString(R.string.direct_install));
                    }
                    new AlertDialog.Builder(getActivity())
                        .setTitle(R.string.select_method)
                        .setItems(
                            options.toArray(new String [0]),
                            (dialog, idx) -> {
                                DownloadReceiver receiver = null;
                                switch (idx) {
                                    case 1:
                                        if (mm.remoteMagiskVersionCode < 1370) {
                                            mm.toast(R.string.no_boot_file_patch_support, Toast.LENGTH_LONG);
                                            return;
                                        }
                                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                                        intent.setType("*/*");
                                        startActivityForResult(intent, SELECT_BOOT_IMG);
                                        return;
                                    case 0:
                                        receiver = new DownloadReceiver() {
                                            @Override
                                            public void onDownloadDone(Uri uri) {
                                                Utils.showUriSnack(getActivity(), uri);
                                            }
                                        };
                                        break;
                                    case 2:
                                        receiver = new DownloadReceiver() {
                                            @Override
                                            public void onDownloadDone(Uri uri) {
                                                Intent intent = new Intent(getActivity(), FlashActivity.class);
                                                intent.setData(uri)
                                                        .putExtra(FlashActivity.SET_BOOT, boot)
                                                        .putExtra(FlashActivity.SET_ENC, keepEncChkbox.isChecked())
                                                        .putExtra(FlashActivity.SET_VERITY, keepVerityChkbox.isChecked())
                                                        .putExtra(FlashActivity.SET_ACTION, FlashActivity.FLASH_MAGISK);
                                                startActivity(intent);
                                            }
                                        };
                                        break;
                                }
                                Utils.dlAndReceive(
                                        getActivity(),
                                        receiver,
                                        mm.magiskLink,
                                        Utils.getLegalFilename(filename)
                                );
                            }
                        ).show();
                }
            )
            .setNeutralButton(R.string.release_notes, (d, i) -> {
                if (mm.releaseNoteLink != null) {
                    Intent openReleaseNoteLink = new Intent(Intent.ACTION_VIEW, Uri.parse(mm.releaseNoteLink));
                    openReleaseNoteLink.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mm.startActivity(openReleaseNoteLink);
                }
            })
            .setNegativeButton(R.string.no_thanks, null)
            .show();
    }

    @OnClick(R.id.uninstall_button)
    public void uninstall() {
        new AlertDialogBuilder(getActivity())
                .setTitle(R.string.uninstall_magisk_title)
                .setMessage(R.string.uninstall_magisk_msg)
                .setPositiveButton(R.string.yes, (d, i) -> {
                    try {
                        InputStream in = mm.getAssets().open(UNINSTALLER);
                        File uninstaller = new File(mm.getCacheDir(), UNINSTALLER);
                        FileOutputStream out = new FileOutputStream(uninstaller);
                        byte[] bytes = new byte[1024];
                        int read;
                        while ((read = in.read(bytes)) != -1) {
                            out.write(bytes, 0, read);
                        }
                        in.close();
                        out.close();
                        in = mm.getAssets().open(UTIL_FUNCTIONS);
                        File utils = new File(mm.getCacheDir(), UTIL_FUNCTIONS);
                        out = new FileOutputStream(utils);
                        while ((read = in.read(bytes)) != -1) {
                            out.write(bytes, 0, read);
                        }
                        in.close();
                        out.close();
                        ProgressDialog progress = new ProgressDialog(getActivity());
                        progress.setTitle(R.string.reboot);
                        progress.show();
                        new CountDownTimer(5000, 1000) {
                            @Override
                            public void onTick(long millisUntilFinished) {
                                progress.setMessage(getString(R.string.reboot_countdown, millisUntilFinished / 1000));
                            }

                            @Override
                            public void onFinish() {
                                progress.setMessage(getString(R.string.reboot_countdown, 0));
                                getShell().su_raw(
                                        "mv -f " + uninstaller + " /cache/" + UNINSTALLER,
                                        "mv -f " + utils + " /data/magisk/" + UTIL_FUNCTIONS,
                                        "reboot"
                                );
                            }
                        }.start();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                })
                .setNegativeButton(R.string.no_thanks, null)
                .show();
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_magisk, container, false);
        unbinder = ButterKnife.bind(this, v);
        getActivity().setTitle(R.string.magisk);

        mm = getApplication();

        expandableContainer.expandLayout = expandLayout;
        setupExpandable();

        mSwipeRefreshLayout.setOnRefreshListener(this);
        updateUI();

        return v;
    }

    @Override
    public void onRefresh() {
        mm.getMagiskInfo();
        updateUI();

        magiskUpdateText.setText(R.string.checking_for_updates);
        magiskUpdateProgress.setVisibility(View.VISIBLE);
        magiskUpdateIcon.setVisibility(View.GONE);

        safetyNetStatusText.setText(R.string.safetyNet_check_text);

        mm.safetyNetDone.hasPublished = false;
        mm.updateCheckDone.hasPublished = false;
        mm.remoteMagiskVersionString = null;
        mm.remoteMagiskVersionCode = -1;
        collapse();

        shownDialog = false;

        // Trigger state check
        if (Utils.checkNetworkStatus(mm)) {
            new CheckUpdates(getActivity()).exec();
        } else {
            mSwipeRefreshLayout.setRefreshing(false);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SELECT_BOOT_IMG && resultCode == Activity.RESULT_OK && data != null) {
            Utils.dlAndReceive(
                getActivity(),
                new DownloadReceiver() {
                    @Override
                    public void onDownloadDone(Uri uri) {
                        Intent intent = new Intent(getActivity(), FlashActivity.class);
                        intent.setData(uri)
                                .putExtra(FlashActivity.SET_BOOT, data.getData())
                                .putExtra(FlashActivity.SET_ENC, keepEncChkbox.isChecked())
                                .putExtra(FlashActivity.SET_VERITY, keepVerityChkbox.isChecked())
                                .putExtra(FlashActivity.SET_ACTION, FlashActivity.PATCH_BOOT);
                        startActivity(intent);
                    }
                },
                mm.magiskLink,
                Utils.getLegalFilename("Magisk-v" + mm.remoteMagiskVersionString + ".zip")
            );
        }
    }

    @Override
    public void onTopicPublished(Topic topic) {
        if (topic == mm.updateCheckDone) {
            updateCheckUI();
        } else if (topic == mm.safetyNetDone) {
            updateSafetyNetUI();
        }
    }

    @Override
    public Topic[] getSubscription() {
        return new Topic[] { mm.updateCheckDone, mm.safetyNetDone };
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbinder.unbind();
    }

    @Override
    public Container getContainer() {
        return expandableContainer;
    }

    private void updateUI() {
        ((MainActivity) getActivity()).checkHideSection();

        boolean hasNetwork = Utils.checkNetworkStatus(getActivity());
        boolean hasRoot = Shell.rootAccess();
        boolean isUpToDate = mm.magiskVersionCode > 1300;

        magiskUpdateCard.setVisibility(hasNetwork ? View.VISIBLE : View.GONE);
        safetyNetCard.setVisibility(hasNetwork ? View.VISIBLE : View.GONE);
        bootImageCard.setVisibility(hasNetwork && hasRoot ? View.VISIBLE : View.GONE);
        installOptionCard.setVisibility(hasNetwork ? View.VISIBLE : View.GONE);
        installButton.setVisibility(hasNetwork ? View.VISIBLE : View.GONE);
        uninstallButton.setVisibility(isUpToDate && hasRoot ? View.VISIBLE : View.GONE);

        int image, color;

        if (mm.magiskVersionCode < 0) {
            color = colorBad;
            image = R.drawable.ic_cancel;
            magiskVersionText.setText(R.string.magisk_version_error);
        } else {
            color = colorOK;
            image = R.drawable.ic_check_circle;
            magiskVersionText.setText(getString(R.string.current_magisk_title, "v" + mm.magiskVersionString));
        }

        magiskStatusIcon.setImageResource(image);
        magiskStatusIcon.setColorFilter(color);

        switch (Shell.rootStatus) {
            case 0:
                color = colorBad;
                image = R.drawable.ic_cancel;
                rootStatusText.setText(R.string.not_rooted);
                break;
            case 1:
                if (mm.suVersion != null) {
                    color = colorOK;
                    image = R.drawable.ic_check_circle;
                    rootStatusText.setText(mm.suVersion);
                    break;
                }
            case -1:
            default:
                color = colorNeutral;
                image = R.drawable.ic_help;
                rootStatusText.setText(R.string.root_error);
        }

        rootStatusIcon.setImageResource(image);
        rootStatusIcon.setColorFilter(color);

        List<String> items = new ArrayList<>();
        if (mm.bootBlock != null) {
            items.add(getString(R.string.auto_detect, mm.bootBlock));
            spinner.setEnabled(false);
        } else {
            items.add(getString(R.string.cannot_auto_detect));
            items.addAll(mm.blockList);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getActivity(),
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void updateCheckUI() {
        int image, color;

        if (mm.remoteMagiskVersionCode < 0) {
            color = colorNeutral;
            image = R.drawable.ic_help;
            magiskUpdateText.setText(R.string.cannot_check_updates);
        } else {
            color = colorOK;
            image = R.drawable.ic_check_circle;
            magiskUpdateText.setText(getString(R.string.install_magisk_title, "v" + mm.remoteMagiskVersionString));
        }

        if (mm.remoteManagerVersionCode > BuildConfig.VERSION_CODE) {
            installText.setText(getString(R.string.update, getString(R.string.app_name)));
        } else if (mm.magiskVersionCode > 0 && mm.remoteMagiskVersionCode > mm.magiskVersionCode) {
            installText.setText(getString(R.string.update, getString(R.string.magisk)));
        } else {
            installText.setText(R.string.install);
        }

        if (!shownDialog && (mm.remoteMagiskVersionCode > mm.magiskVersionCode
                || mm.remoteManagerVersionCode > BuildConfig.VERSION_CODE)) {
                install();
        }

        magiskUpdateIcon.setImageResource(image);
        magiskUpdateIcon.setColorFilter(color);
        magiskUpdateIcon.setVisibility(View.VISIBLE);

        magiskUpdateProgress.setVisibility(View.GONE);
        mSwipeRefreshLayout.setRefreshing(false);
    }

    private void updateSafetyNetUI() {
        int image, color;
        safetyNetProgress.setVisibility(View.GONE);
        safetyNetRefreshIcon.setVisibility(View.VISIBLE);
        if (mm.SNCheckResult.failed) {
            safetyNetStatusText.setText(mm.SNCheckResult.errmsg);
            collapse();
        } else {
            safetyNetStatusText.setText(R.string.safetyNet_check_success);
            if (mm.SNCheckResult.ctsProfile) {
                color = colorOK;
                image = R.drawable.ic_check_circle;
            } else {
                color = colorBad;
                image = R.drawable.ic_cancel;
            }
            ctsStatusText.setText("ctsProfile: " + mm.SNCheckResult.ctsProfile);
            ctsStatusIcon.setImageResource(image);
            ctsStatusIcon.setColorFilter(color);

            if (mm.SNCheckResult.basicIntegrity) {
                color = colorOK;
                image = R.drawable.ic_check_circle;
            } else {
                color = colorBad;
                image = R.drawable.ic_cancel;
            }
            basicStatusText.setText("basicIntegrity: " + mm.SNCheckResult.basicIntegrity);
            basicStatusIcon.setImageResource(image);
            basicStatusIcon.setColorFilter(color);
            expand();
        }
    }
}

