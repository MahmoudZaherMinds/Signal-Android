package org.thoughtcrime.securesms;

import android.Manifest;
import android.animation.Animator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.dd.CircularProgressButton;
import com.google.android.gms.auth.api.phone.SmsRetriever;
import com.google.android.gms.auth.api.phone.SmsRetrieverClient;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.tasks.Task;
import com.google.i18n.phonenumbers.AsYouTypeFormatter;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import net.sqlcipher.database.SQLiteDatabase;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.thoughtcrime.securesms.animation.AnimationCompleteListener;
import org.thoughtcrime.securesms.backup.FullBackupBase;
import org.thoughtcrime.securesms.backup.FullBackupImporter;
import org.thoughtcrime.securesms.components.LabeledEditText;
import org.thoughtcrime.securesms.components.registration.CallMeCountDownView;
import org.thoughtcrime.securesms.components.registration.VerificationCodeView;
import org.thoughtcrime.securesms.components.registration.VerificationPinKeyboard;
import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider;
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil;
import org.thoughtcrime.securesms.crypto.PreKeyUtil;
import org.thoughtcrime.securesms.crypto.SessionUtil;
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.IdentityDatabase;
import org.thoughtcrime.securesms.database.NoExternalStorageException;
import org.thoughtcrime.securesms.gcm.FcmUtil;
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob;
import org.thoughtcrime.securesms.jobs.RotateCertificateJob;
import org.thoughtcrime.securesms.lock.RegistrationLockReminders;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.push.AccountManagerFactory;
import org.thoughtcrime.securesms.registration.CaptchaActivity;
import org.thoughtcrime.securesms.service.DirectoryRefreshListener;
import org.thoughtcrime.securesms.service.RotateSignedPreKeyListener;
import org.thoughtcrime.securesms.service.VerificationCodeParser;
import org.thoughtcrime.securesms.util.BackupUtil;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.Dialogs;
import org.thoughtcrime.securesms.util.GenaralUtils.Constants;
import org.thoughtcrime.securesms.util.GenaralUtils.GenaralUtils;
import org.thoughtcrime.securesms.util.PlayServicesUtil;
import org.thoughtcrime.securesms.util.PlayServicesUtil.PlayServicesStatus;
import org.thoughtcrime.securesms.util.ServiceUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.concurrent.AssertedSuccessListener;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.exceptions.CaptchaRequiredException;
import org.whispersystems.signalservice.api.push.exceptions.RateLimitException;
import org.whispersystems.signalservice.api.util.PhoneNumberFormatter;
import org.whispersystems.signalservice.internal.push.LockedException;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The register account activity.  Prompts ths user for their registration information
 * and begins the account registration process.
 *
 * @author Moxie Marlinspike
 */
public class RegistrationActivity extends BaseActionBarActivity implements VerificationCodeView.OnCodeEnteredListener {

    public static final String RE_REGISTRATION_EXTRA = "re_registration";
    private static final int PICK_COUNTRY = 1;
    private static final int CAPTCHA = 24601;
    private static final int SCENE_TRANSITION_DURATION = 250;
    private static final int DEBUG_TAP_TARGET = 8;
    private static final int DEBUG_TAP_ANNOUNCE = 4;
    private static final String TAG = RegistrationActivity.class.getSimpleName();

    private AsYouTypeFormatter countryFormatter;
    private ArrayAdapter<String> countrySpinnerAdapter;
    private Spinner countrySpinner;
    private LabeledEditText countryCode;
    private LabeledEditText number;
    private CircularProgressButton createButton;
    private TextView title;
    private TextView subtitle;
    private View registrationContainer;
    private View verificationContainer;

    private View restoreContainer;
    private TextView restoreBackupTime;
    private TextView restoreBackupSize;
    private TextView restoreBackupProgress;
    private CircularProgressButton restoreButton;

    private View pinContainer;
    private EditText pin;
    private CircularProgressButton pinButton;
    private TextView pinForgotButton;
    private View pinClarificationContainer;

    private CallMeCountDownView callMeCountDownView;
    private View wrongNumberButton;
    private VerificationPinKeyboard keyboard;
    private VerificationCodeView verificationCodeView;
    private RegistrationState registrationState;
    private SmsRetrieverReceiver smsRetrieverReceiver;
    private SignalServiceAccountManager accountManager;
    private int debugTapCounter;


    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.registration_activity);

        initializeResources();
        initializeSpinner();
        initializeNumber();
        initializeBackupDetection();
        initializeChallengeListener();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        shutdownChallengeListener();
        markAsVerifying(false);
        EventBus.getDefault().unregister(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_COUNTRY && resultCode == RESULT_OK && data != null) {
            this.countryCode.setText(String.valueOf(data.getIntExtra("country_code", 1)));
            setCountryDisplay(data.getStringExtra("country_name"));
            setCountryFormatter(data.getIntExtra("country_code", 1));
        } else if (requestCode == CAPTCHA && resultCode == RESULT_OK && data != null) {
            registrationState = new RegistrationState(Optional.fromNullable(data.getStringExtra(CaptchaActivity.KEY_TOKEN)), registrationState);

            if (data.getBooleanExtra(CaptchaActivity.KEY_IS_SMS, true)) {
                handleRegister();
            } else {
                handlePhoneCallRequest();
            }
        } else if (requestCode == CAPTCHA) {
            Toast.makeText(this, R.string.RegistrationActivity_failed_to_verify_the_captcha, Toast.LENGTH_LONG).show();
            createButton.setIndeterminateProgressMode(false);
            createButton.setProgress(0);
        }
    }

    private void initializeResources() {
        TextView skipButton = findViewById(R.id.skip_button);
        TextView restoreSkipButton = findViewById(R.id.skip_restore_button);

        this.countrySpinner = findViewById(R.id.country_spinner);
        this.countryCode = findViewById(R.id.country_code);
        this.number = findViewById(R.id.number);
        this.createButton = findViewById(R.id.registerButton);
        this.title = findViewById(R.id.verify_header);
        this.subtitle = findViewById(R.id.verify_subheader);
        this.registrationContainer = findViewById(R.id.registration_container);
        this.verificationContainer = findViewById(R.id.verification_container);

        this.verificationCodeView = findViewById(R.id.code);
        this.keyboard = findViewById(R.id.keyboard);
        this.callMeCountDownView = findViewById(R.id.call_me_count_down);
        this.wrongNumberButton = findViewById(R.id.wrong_number);

        this.restoreContainer = findViewById(R.id.restore_container);
        this.restoreBackupSize = findViewById(R.id.backup_size_text);
        this.restoreBackupTime = findViewById(R.id.backup_created_text);
        this.restoreBackupProgress = findViewById(R.id.backup_progress_text);
        this.restoreButton = findViewById(R.id.restore_button);

        this.pinContainer = findViewById(R.id.pin_container);
        this.pin = findViewById(R.id.pin);
        this.pinButton = findViewById(R.id.pinButton);
        this.pinForgotButton = findViewById(R.id.forgot_button);
        this.pinClarificationContainer = findViewById(R.id.pin_clarification_container);

        this.registrationState = new RegistrationState(RegistrationState.State.INITIAL, null, null, Optional.absent(), Optional.absent());

        this.countryCode.getInput().addTextChangedListener(new CountryCodeChangedListener());
        this.number.getInput().addTextChangedListener(new NumberChangedListener());
        this.createButton.setOnClickListener(v -> handleRegister());
        this.callMeCountDownView.setOnClickListener(v -> handlePhoneCallRequest());

        skipButton.setOnClickListener(v -> handleCancel());
        restoreSkipButton.setOnClickListener(v -> displayInitialView(true));

        if (getIntent().getBooleanExtra(RE_REGISTRATION_EXTRA, false)) {
            skipButton.setVisibility(View.VISIBLE);
        } else {
            skipButton.setVisibility(View.INVISIBLE);
        }

        this.keyboard.setOnKeyPressListener(key -> {
            if (key >= 0) verificationCodeView.append(key);
            else verificationCodeView.delete();
        });

        this.verificationCodeView.setOnCompleteListener(this);
        EventBus.getDefault().register(this);
    }

    private void onDebugClick(View view) {
        debugTapCounter++;

        if (debugTapCounter >= DEBUG_TAP_TARGET) {
            startActivity(new Intent(this, LogSubmitActivity.class));
        } else if (debugTapCounter >= DEBUG_TAP_ANNOUNCE) {
            int remaining = DEBUG_TAP_TARGET - debugTapCounter;
            Toast.makeText(this, getResources().getQuantityString(R.plurals.RegistrationActivity_debug_log_hint, remaining, remaining), Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initializeSpinner() {
        this.countrySpinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        this.countrySpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        setCountryDisplay(getString(R.string.RegistrationActivity_select_your_country));

        this.countrySpinner.setAdapter(this.countrySpinnerAdapter);
        this.countrySpinner.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                Intent intent = new Intent(RegistrationActivity.this, CountrySelectionActivity.class);
                startActivityForResult(intent, PICK_COUNTRY);
            }
            return true;
        });
        this.countrySpinner.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.getAction() == KeyEvent.ACTION_UP) {
                Intent intent = new Intent(RegistrationActivity.this, CountrySelectionActivity.class);
                startActivityForResult(intent, PICK_COUNTRY);
                return true;
            }
            return false;
        });
    }

    @SuppressLint("MissingPermission")
    private void initializeNumber() {
        Optional<Phonenumber.PhoneNumber> localNumber = Optional.absent();

        if (Permissions.hasAll(this, Manifest.permission.READ_PHONE_STATE)) {
            localNumber = Util.getDeviceNumber(this);
        }

        if (localNumber.isPresent()) {
            this.countryCode.setText(String.valueOf(localNumber.get().getCountryCode()));
            this.number.setText(String.valueOf(localNumber.get().getNationalNumber()));
        } else {
            Optional<String> simCountryIso = Util.getSimCountryIso(this);

            if (simCountryIso.isPresent() && !TextUtils.isEmpty(simCountryIso.get())) {
                this.countryCode.setText(String.valueOf(PhoneNumberUtil.getInstance().getCountryCodeForRegion(simCountryIso.get())));
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    private void initializeBackupDetection() {
        if (!Permissions.hasAll(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            Log.i(TAG, "Skipping backup detection. We don't have the permission.");
            return;
        }

        if (getIntent().getBooleanExtra(RE_REGISTRATION_EXTRA, false)) return;

        new AsyncTask<Void, Void, BackupUtil.BackupInfo>() {
            @Override
            protected @Nullable
            BackupUtil.BackupInfo doInBackground(Void... voids) {
                try {
                    return BackupUtil.getLatestBackup();
                } catch (NoExternalStorageException e) {
                    Log.w(TAG, e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(@Nullable BackupUtil.BackupInfo backup) {
                if (backup != null) displayRestoreView(backup);
            }
        }.execute();
    }

    private void setCountryDisplay(String value) {
        this.countrySpinnerAdapter.clear();
        this.countrySpinnerAdapter.add(value);
    }

    private void setCountryFormatter(int countryCode) {
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        String regionCode = util.getRegionCodeForCountryCode(countryCode);

        if (regionCode == null) this.countryFormatter = null;
        else this.countryFormatter = util.getAsYouTypeFormatter(regionCode);
    }

    private String getConfiguredE164Number() {
        return PhoneNumberFormatter.formatE164(countryCode.getText().toString(),
                number.getText().toString());
    }

    @SuppressLint("StaticFieldLeak")
    private void handleRestore(BackupUtil.BackupInfo backup) {
        View view = LayoutInflater.from(this).inflate(R.layout.enter_backup_passphrase_dialog, null);
        EditText prompt = view.findViewById(R.id.restore_passphrase_input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.RegistrationActivity_enter_backup_passphrase)
                .setView(view)
                .setPositiveButton(getString(R.string.RegistrationActivity_restore), (dialog, which) -> {
                    InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    inputMethodManager.hideSoftInputFromWindow(prompt.getWindowToken(), 0);

                    restoreButton.setIndeterminateProgressMode(true);
                    restoreButton.setProgress(50);

                    final String passphrase = prompt.getText().toString();

                    new AsyncTask<Void, Void, BackupImportResult>() {
                        @Override
                        protected BackupImportResult doInBackground(Void... voids) {
                            try {
                                Context context = RegistrationActivity.this;
                                SQLiteDatabase database = DatabaseFactory.getBackupDatabase(context);

                                FullBackupImporter.importFile(context,
                                        AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret(),
                                        database, backup.getFile(), passphrase);

                                DatabaseFactory.upgradeRestored(context, database);
                                NotificationChannels.restoreContactNotificationChannels(context);

                                TextSecurePreferences.setBackupEnabled(context, true);
                                TextSecurePreferences.setBackupPassphrase(context, passphrase);
                                return BackupImportResult.SUCCESS;
                            } catch (FullBackupImporter.DatabaseDowngradeException e) {
                                Log.w(TAG, "Failed due to the backup being from a newer version of Signal.", e);
                                return BackupImportResult.FAILURE_VERSION_DOWNGRADE;
                            } catch (IOException e) {
                                Log.w(TAG, e);
                                return BackupImportResult.FAILURE_UNKNOWN;
                            }
                        }

                        @Override
                        protected void onPostExecute(@NonNull BackupImportResult result) {
                            restoreButton.setIndeterminateProgressMode(false);
                            restoreButton.setProgress(0);
                            restoreBackupProgress.setText("");

                            switch (result) {
                                case SUCCESS:
                                    displayInitialView(true);
                                    break;
                                case FAILURE_VERSION_DOWNGRADE:
                                    Toast.makeText(RegistrationActivity.this, R.string.RegistrationActivity_backup_failure_downgrade, Toast.LENGTH_LONG).show();
                                    break;
                                case FAILURE_UNKNOWN:
                                    Toast.makeText(RegistrationActivity.this, R.string.RegistrationActivity_incorrect_backup_passphrase, Toast.LENGTH_LONG).show();
                                    break;
                            }
                        }
                    }.execute();

                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void handleRegister() {
        if (TextUtils.isEmpty(countryCode.getText())) {
            Toast.makeText(this, getString(R.string.RegistrationActivity_you_must_specify_your_country_code), Toast.LENGTH_LONG).show();
            return;
        }

        if (TextUtils.isEmpty(number.getText())) {
            Toast.makeText(this, getString(R.string.RegistrationActivity_you_must_specify_your_phone_number), Toast.LENGTH_LONG).show();
            return;
        }

        final String e164number = getConfiguredE164Number();

        if (!PhoneNumberFormatter.isValidNumber(e164number, countryCode.getText().toString())) {
            Dialogs.showAlertDialog(this,
                    getString(R.string.RegistrationActivity_invalid_number),
                    String.format(getString(R.string.RegistrationActivity_the_number_you_specified_s_is_invalid), e164number));
            return;
        }

        PlayServicesStatus gcmStatus = PlayServicesUtil.getPlayServicesStatus(this);

        if (gcmStatus == PlayServicesStatus.SUCCESS) {
            handleRequestVerification(e164number, true);
        } else if (gcmStatus == PlayServicesStatus.MISSING) {
            handlePromptForNoPlayServices(e164number);
        } else if (gcmStatus == PlayServicesStatus.NEEDS_UPDATE) {
            GoogleApiAvailability.getInstance().getErrorDialog(this, ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, 0).show();
        } else {
            Dialogs.showAlertDialog(this, getString(R.string.RegistrationActivity_play_services_error),
                    getString(R.string.RegistrationActivity_google_play_services_is_updating_or_unavailable));
        }
    }

    private void handleRequestVerification(@NonNull String e164number, boolean gcmSupported) {
        createButton.setIndeterminateProgressMode(true);
        createButton.setProgress(50);

        if (gcmSupported) {
            SmsRetrieverClient client = SmsRetriever.getClient(this);
            Task<Void> task = client.startSmsRetriever();

            task.addOnSuccessListener(none -> {
                Log.i(TAG, "Successfully registered SMS listener.");
                requestVerificationCode(e164number, true, true);
            });

            task.addOnFailureListener(e -> {
                Log.w(TAG, "Failed to register SMS listener.", e);
                requestVerificationCode(e164number, true, false);
            });
        } else {
            requestVerificationCode(e164number, false, false);
        }
    }

    // request verification code with phone number
    @SuppressLint("StaticFieldLeak")
    private void requestVerificationCode(@NonNull String e164number, boolean gcmSupported, boolean smsRetrieverSupported) {
        new AsyncTask<Void, Void, VerificationRequestResult>() {
            @Override
            protected @NonNull
            VerificationRequestResult doInBackground(Void... voids) {
                try {
                    markAsVerifying(true);

                    String password = Util.getSecret(18);

                    Optional<String> fcmToken;

                    if (gcmSupported) {
                        fcmToken = FcmUtil.getToken();
                    } else {
                        fcmToken = Optional.absent();
                    }

                    accountManager = AccountManagerFactory.createManager(RegistrationActivity.this, e164number, password);
                    accountManager.requestSmsVerificationCode(smsRetrieverSupported, registrationState.captchaToken);

                    return new VerificationRequestResult(password, fcmToken, Optional.absent());
                } catch (IOException e) {
                    Log.w(TAG, "Error during account registration", e);
                    return new VerificationRequestResult(null, Optional.absent(), Optional.of(e));
                }
            }

            protected void onPostExecute(@NonNull VerificationRequestResult result) {
                if (result.exception.isPresent() && result.exception.get() instanceof CaptchaRequiredException) {
                    requestCaptcha(true);
                } else if (result.exception.isPresent()) {
                    Toast.makeText(RegistrationActivity.this, R.string.RegistrationActivity_unable_to_connect_to_service, Toast.LENGTH_LONG).show();
                    createButton.setIndeterminateProgressMode(false);
                    createButton.setProgress(0);
                } else {

                    // this mean this step done successfully // store phone number
                    GenaralUtils.cacheString(RegistrationActivity.this, Constants.USER_PHONE, e164number);

                    registrationState = new RegistrationState(RegistrationState.State.VERIFYING, e164number, result.password, result.fcmToken, Optional.absent());
                    displayVerificationView(e164number, 64);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void requestCaptcha(boolean isSms) {
        startActivityForResult(CaptchaActivity.getIntent(this, isSms), CAPTCHA);
    }

    private void handleVerificationCodeReceived(@Nullable String code) {
        List<Integer> parsedCode = convertVerificationCodeToDigits(code);

        for (int i = 0; i < parsedCode.size(); i++) {
            int index = i;
            verificationCodeView.postDelayed(() -> verificationCodeView.append(parsedCode.get(index)), i * 200);
        }
    }

    private List<Integer> convertVerificationCodeToDigits(@Nullable String code) {
        if (code == null || code.length() != 6 || registrationState.state != RegistrationState.State.VERIFYING) {
            return Collections.emptyList();
        }

        List<Integer> result = new LinkedList<>();

        try {
            for (int i = 0; i < code.length(); i++) {
                result.add(Integer.parseInt(Character.toString(code.charAt(i))));
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, "Failed to convert code into digits.", e);
            return Collections.emptyList();
        }

        return result;
    }

    @SuppressLint("StaticFieldLeak")
    @Override
    public void onCodeComplete(@NonNull String code) {
        this.registrationState = new RegistrationState(RegistrationState.State.CHECKING, this.registrationState);
        callMeCountDownView.setVisibility(View.INVISIBLE);
        keyboard.displayProgress();

        new AsyncTask<Void, Void, Pair<Integer, Long>>() {
            @Override
            protected Pair<Integer, Long> doInBackground(Void... voids) {
                try {
                    verifyAccount(code, null);
                    return new Pair<>(1, -1L);
                } catch (LockedException e) {
                    Log.w(TAG, e);
                    return new Pair<>(2, e.getTimeRemaining());
                } catch (IOException e) {
                    Log.w(TAG, e);
                    return new Pair<>(3, -1L);
                }
            }

            @Override
            protected void onPostExecute(Pair<Integer, Long> result) {
                if (result.first == 1) {
                    keyboard.displaySuccess().addListener(new AssertedSuccessListener<Boolean>() {
                        @Override
                        public void onSuccess(Boolean result) {
                            handleSuccessfulRegistration();
                        }
                    });
                } else if (result.first == 2) {
                    keyboard.displayLocked().addListener(new AssertedSuccessListener<Boolean>() {
                        @Override
                        public void onSuccess(Boolean r) {
                            registrationState = new RegistrationState(RegistrationState.State.PIN, registrationState);
                            displayPinView(code, result.second);
                        }
                    });
                } else {
                    keyboard.displayFailure().addListener(new AssertedSuccessListener<Boolean>() {
                        @Override
                        public void onSuccess(Boolean result) {
                            registrationState = new RegistrationState(RegistrationState.State.VERIFYING, registrationState);
                            callMeCountDownView.setVisibility(View.VISIBLE);
                            verificationCodeView.clear();
                            keyboard.displayKeyboard();
                        }
                    });
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @SuppressLint("StaticFieldLeak")
    private void handleVerifyWithPinClicked(@NonNull String code, @Nullable String pin) {
        if (TextUtils.isEmpty(pin) || TextUtils.isEmpty(pin.replace(" ", ""))) {
            Toast.makeText(this, R.string.RegistrationActivity_you_must_enter_your_registration_lock_PIN, Toast.LENGTH_LONG).show();
            return;
        }

        pinButton.setIndeterminateProgressMode(true);
        pinButton.setProgress(50);

        new AsyncTask<Void, Void, Integer>() {
            @Override
            protected Integer doInBackground(Void... voids) {
                try {
                    verifyAccount(code, pin);
                    return 1;
                } catch (LockedException e) {
                    Log.w(TAG, e);
                    return 2;
                } catch (RateLimitException e) {
                    Log.w(TAG, e);
                    return 3;
                } catch (IOException e) {
                    Log.w(TAG, e);
                    return 4;
                }
            }

            @Override
            protected void onPostExecute(Integer result) {
                pinButton.setIndeterminateProgressMode(false);
                pinButton.setProgress(0);

                if (result == 1) {
                    TextSecurePreferences.setRegistrationLockPin(RegistrationActivity.this, pin);
                    TextSecurePreferences.setRegistrationtLockEnabled(RegistrationActivity.this, true);
                    TextSecurePreferences.setRegistrationLockLastReminderTime(RegistrationActivity.this, System.currentTimeMillis());
                    TextSecurePreferences.setRegistrationLockNextReminderInterval(RegistrationActivity.this, RegistrationLockReminders.INITIAL_INTERVAL);

                    handleSuccessfulRegistration();
                } else if (result == 2) {
                    RegistrationActivity.this.pin.setText("");
                    Toast.makeText(RegistrationActivity.this, R.string.RegistrationActivity_incorrect_registration_lock_pin, Toast.LENGTH_LONG).show();
                } else if (result == 3) {
                    new AlertDialog.Builder(RegistrationActivity.this)
                            .setTitle(R.string.RegistrationActivity_too_many_attempts)
                            .setMessage(R.string.RegistrationActivity_you_have_made_too_many_incorrect_registration_lock_pin_attempts_please_try_again_in_a_day)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else if (result == 4) {
                    Toast.makeText(RegistrationActivity.this, R.string.RegistrationActivity_error_connecting_to_service, Toast.LENGTH_LONG).show();
                }
            }
        }.execute();
    }

    private void handleForgottenPin(long timeRemaining) {
        new AlertDialog.Builder(RegistrationActivity.this)
                .setTitle(R.string.RegistrationActivity_oh_no)
                .setMessage(getString(R.string.RegistrationActivity_registration_of_this_phone_number_will_be_possible_without_your_registration_lock_pin_after_seven_days_have_passed, (TimeUnit.MILLISECONDS.toDays(timeRemaining) + 1)))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @SuppressLint("StaticFieldLeak")
    private void handlePhoneCallRequest() {
        if (registrationState.state == RegistrationState.State.VERIFYING) {
            callMeCountDownView.startCountDown(300);

            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... voids) {
                    try {
                        accountManager.requestVoiceVerificationCode(Locale.getDefault(), registrationState.captchaToken);
                    } catch (CaptchaRequiredException e) {
                        requestCaptcha(false);
                    } catch (IOException e) {
                        Log.w(TAG, e);
                    }

                    return null;
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    private void verifyAccount(@NonNull String code, @Nullable String pin) throws IOException {
        int registrationId = KeyHelper.generateRegistrationId(false);
        byte[] unidentifiedAccessKey = UnidentifiedAccessUtil.getSelfUnidentifiedAccessKey(RegistrationActivity.this);
        boolean universalUnidentifiedAccess = TextSecurePreferences.isUniversalUnidentifiedAccess(RegistrationActivity.this);

        TextSecurePreferences.setLocalRegistrationId(RegistrationActivity.this, registrationId);
        SessionUtil.archiveAllSessions(RegistrationActivity.this);

        accountManager.verifyAccountWithCode(code, null, registrationId, !registrationState.gcmToken.isPresent(), pin,
                unidentifiedAccessKey, universalUnidentifiedAccess);

        IdentityKeyPair identityKey = IdentityKeyUtil.getIdentityKeyPair(RegistrationActivity.this);
        List<PreKeyRecord> records = PreKeyUtil.generatePreKeys(RegistrationActivity.this);
        SignedPreKeyRecord signedPreKey = PreKeyUtil.generateSignedPreKey(RegistrationActivity.this, identityKey, true);

        accountManager.setPreKeys(identityKey.getPublicKey(), signedPreKey, records);

        if (registrationState.gcmToken.isPresent()) {
            accountManager.setGcmId(registrationState.gcmToken);
        }

        TextSecurePreferences.setFcmToken(RegistrationActivity.this, registrationState.gcmToken.orNull());
        TextSecurePreferences.setFcmDisabled(RegistrationActivity.this, !registrationState.gcmToken.isPresent());
        TextSecurePreferences.setWebsocketRegistered(RegistrationActivity.this, true);

        DatabaseFactory.getIdentityDatabase(RegistrationActivity.this)
                .saveIdentity(Address.fromSerialized(registrationState.e164number),
                        identityKey.getPublicKey(), IdentityDatabase.VerifiedStatus.VERIFIED,
                        true, System.currentTimeMillis(), true);

        TextSecurePreferences.setVerifying(RegistrationActivity.this, false);
        TextSecurePreferences.setPushRegistered(RegistrationActivity.this, true);
        TextSecurePreferences.setLocalNumber(RegistrationActivity.this, registrationState.e164number);
        TextSecurePreferences.setPushServerPassword(RegistrationActivity.this, registrationState.password);
        TextSecurePreferences.setSignedPreKeyRegistered(RegistrationActivity.this, true);
        TextSecurePreferences.setPromptedPushRegistration(RegistrationActivity.this, true);
        TextSecurePreferences.setUnauthorizedReceived(RegistrationActivity.this, false);
    }

    private void handleSuccessfulRegistration() {
        ApplicationContext.getInstance(RegistrationActivity.this).getJobManager().add(new DirectoryRefreshJob(false));
        ApplicationContext.getInstance(RegistrationActivity.this).getJobManager().add(new RotateCertificateJob(RegistrationActivity.this));

        DirectoryRefreshListener.schedule(RegistrationActivity.this);
        RotateSignedPreKeyListener.schedule(RegistrationActivity.this);

        Intent nextIntent = getIntent().getParcelableExtra("next_intent");

        if (nextIntent == null) {
            nextIntent = new Intent(RegistrationActivity.this, ConversationListActivity.class);
        }

        startActivity(nextIntent);
        finish();
    }

    private void displayRestoreView(@NonNull BackupUtil.BackupInfo backup) {
        title.animate().translationX(title.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                title.setText(R.string.RegistrationActivity_restore_from_backup);
                title.clearAnimation();
                title.setTranslationX(-1 * title.getWidth());
                title.animate().translationX(0).setListener(null).setInterpolator(new OvershootInterpolator()).setDuration(SCENE_TRANSITION_DURATION).start();
            }
        }).start();

        subtitle.animate().translationX(subtitle.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                subtitle.setText(R.string.RegistrationActivity_restore_your_messages_and_media_from_a_local_backup);
                subtitle.clearAnimation();
                subtitle.setTranslationX(-1 * subtitle.getWidth());
                subtitle.animate().translationX(0).setListener(null).setInterpolator(new OvershootInterpolator()).setDuration(SCENE_TRANSITION_DURATION).start();
            }
        }).start();

        registrationContainer.animate().translationX(registrationContainer.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                registrationContainer.clearAnimation();
                registrationContainer.setVisibility(View.INVISIBLE);
                registrationContainer.setTranslationX(0);

                restoreContainer.setTranslationX(-1 * registrationContainer.getWidth());
                restoreContainer.setVisibility(View.VISIBLE);
                restoreButton.setProgress(0);
                restoreButton.setIndeterminateProgressMode(false);
                restoreButton.setOnClickListener(v -> handleRestore(backup));
                restoreBackupSize.setText(getString(R.string.RegistrationActivity_backup_size_s, Util.getPrettyFileSize(backup.getSize())));
                restoreBackupTime.setText(getString(R.string.RegistrationActivity_backup_timestamp_s, DateUtils.getExtendedRelativeTimeSpanString(RegistrationActivity.this, Locale.US, backup.getTimestamp())));
                restoreBackupProgress.setText("");
                restoreContainer.animate().translationX(0).setDuration(SCENE_TRANSITION_DURATION).setListener(null).setInterpolator(new OvershootInterpolator()).start();
            }
        }).start();
    }

    private void displayInitialView(boolean forwards) {
        int startDirectionMultiplier = forwards ? -1 : 1;
        int endDirectionMultiplier = forwards ? 1 : -1;

        title.animate().translationX(startDirectionMultiplier * title.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                title.setText(R.string.registration_activity__verify_your_number);
                title.clearAnimation();
                title.setTranslationX(endDirectionMultiplier * title.getWidth());
                title.animate().translationX(0).setListener(null).setInterpolator(new OvershootInterpolator()).setDuration(SCENE_TRANSITION_DURATION).start();
            }
        }).start();

        subtitle.animate().translationX(startDirectionMultiplier * subtitle.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                subtitle.setText(R.string.registration_activity__please_enter_your_mobile_number_to_receive_a_verification_code_carrier_rates_may_apply);
                subtitle.clearAnimation();
                subtitle.setTranslationX(endDirectionMultiplier * subtitle.getWidth());
                subtitle.animate().translationX(0).setListener(null).setInterpolator(new OvershootInterpolator()).setDuration(SCENE_TRANSITION_DURATION).start();
            }
        }).start();

        View container;

        if (verificationContainer.getVisibility() == View.VISIBLE)
            container = verificationContainer;
        else container = restoreContainer;

        container.animate().translationX(startDirectionMultiplier * container.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                container.clearAnimation();
                container.setVisibility(View.INVISIBLE);
                container.setTranslationX(0);

                registrationContainer.setTranslationX(endDirectionMultiplier * registrationContainer.getWidth());
                registrationContainer.setVisibility(View.VISIBLE);
                createButton.setProgress(0);
                createButton.setIndeterminateProgressMode(false);
                registrationContainer.animate().translationX(0).setDuration(SCENE_TRANSITION_DURATION).setListener(null).setInterpolator(new OvershootInterpolator()).start();
            }
        }).start();
    }

    private void displayVerificationView(@NonNull String e164number, int callCountdown) {
        ServiceUtil.getInputMethodManager(this)
                .hideSoftInputFromWindow(countryCode.getWindowToken(), 0);

        ServiceUtil.getInputMethodManager(this)
                .hideSoftInputFromWindow(number.getWindowToken(), 0);

        title.animate().translationX(-1 * title.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                title.setText(getString(R.string.RegistrationActivity_enter_the_code_we_sent_to_s, formatNumber(e164number)));
                title.clearAnimation();
                title.setTranslationX(title.getWidth());
                title.animate().translationX(0).setListener(null).setInterpolator(new OvershootInterpolator()).setDuration(SCENE_TRANSITION_DURATION).start();
            }
        }).start();

        subtitle.setText("");

        registrationContainer.animate().translationX(-1 * registrationContainer.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                registrationContainer.clearAnimation();
                registrationContainer.setVisibility(View.INVISIBLE);
                registrationContainer.setTranslationX(0);

                verificationContainer.setTranslationX(verificationContainer.getWidth());
                verificationContainer.setVisibility(View.VISIBLE);
                verificationContainer.animate().translationX(0).setListener(null).setInterpolator(new OvershootInterpolator()).setDuration(SCENE_TRANSITION_DURATION).start();
            }
        }).start();

        this.callMeCountDownView.startCountDown(callCountdown);

        this.wrongNumberButton.setOnClickListener(v -> onWrongNumberClicked());
    }

    private void displayPinView(String code, long lockedUntil) {
        title.animate().translationX(-1 * title.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                title.setText(R.string.RegistrationActivity_registration_lock_pin);
                title.clearAnimation();
                title.setTranslationX(title.getWidth());
                title.animate().translationX(0).setListener(null).setInterpolator(new OvershootInterpolator()).setDuration(SCENE_TRANSITION_DURATION).start();
            }
        }).start();

        subtitle.animate().translationX(-1 * subtitle.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                subtitle.setText(R.string.RegistrationActivity_this_phone_number_has_registration_lock_enabled_please_enter_the_registration_lock_pin);
                subtitle.clearAnimation();
                subtitle.setTranslationX(subtitle.getWidth());
                subtitle.animate().translationX(0).setListener(null).setInterpolator(new OvershootInterpolator()).setDuration(SCENE_TRANSITION_DURATION).start();
            }
        }).start();

        verificationContainer.animate().translationX(-1 * verificationContainer.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                verificationContainer.clearAnimation();
                verificationContainer.setVisibility(View.INVISIBLE);
                verificationContainer.setTranslationX(0);

                pinContainer.setTranslationX(pinContainer.getWidth());
                pinContainer.setVisibility(View.VISIBLE);
                pinContainer.animate().translationX(0).setListener(null).setInterpolator(new OvershootInterpolator()).setDuration(SCENE_TRANSITION_DURATION).start();
            }
        }).start();

        pinButton.setOnClickListener(v -> handleVerifyWithPinClicked(code, pin.getText().toString()));
        pinForgotButton.setOnClickListener(v -> handleForgottenPin(lockedUntil));
        pin.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s != null && code.equals(s.toString()))
                    pinClarificationContainer.setVisibility(View.VISIBLE);
                else if (pinClarificationContainer.getVisibility() == View.VISIBLE)
                    pinClarificationContainer.setVisibility(View.GONE);
            }
        });
    }

    private void handleCancel() {
        TextSecurePreferences.setPromptedPushRegistration(RegistrationActivity.this, true);
        Intent nextIntent = getIntent().getParcelableExtra("next_intent");

        if (nextIntent == null) {
            nextIntent = new Intent(RegistrationActivity.this, ConversationListActivity.class);
        }

        startActivity(nextIntent);
        finish();
    }

    private void handlePromptForNoPlayServices(@NonNull String e164number) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        dialog.setTitle(R.string.RegistrationActivity_missing_google_play_services);
        dialog.setMessage(R.string.RegistrationActivity_this_device_is_missing_google_play_services);
        dialog.setPositiveButton(R.string.RegistrationActivity_i_understand, (dialog1, which) -> handleRequestVerification(e164number, false));
        dialog.setNegativeButton(android.R.string.cancel, null);
        dialog.show();
    }

    private void initializeChallengeListener() {
        smsRetrieverReceiver = new SmsRetrieverReceiver();
        IntentFilter filter = new IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION);
        registerReceiver(smsRetrieverReceiver, filter);
    }

    private void shutdownChallengeListener() {
        if (smsRetrieverReceiver != null) {
            unregisterReceiver(smsRetrieverReceiver);
            smsRetrieverReceiver = null;
        }
    }

    private void markAsVerifying(boolean verifying) {
        TextSecurePreferences.setVerifying(this, verifying);

        if (verifying) {
            TextSecurePreferences.setPushRegistered(this, false);
        }
    }

    private String formatNumber(@NonNull String e164Number) {
        try {
            Phonenumber.PhoneNumber number = PhoneNumberUtil.getInstance().parse(e164Number, null);
            return PhoneNumberUtil.getInstance().format(number, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL);
        } catch (NumberParseException e) {
            return e164Number;
        }
    }

    private void onWrongNumberClicked() {
        displayInitialView(false);
        registrationState = new RegistrationState(RegistrationState.State.INITIAL, null, null, Optional.absent(), Optional.absent());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(FullBackupBase.BackupEvent event) {
        if (event.getCount() == 0)
            restoreBackupProgress.setText(R.string.RegistrationActivity_checking);
        else
            restoreBackupProgress.setText(getString(R.string.RegistrationActivity_d_messages_so_far, event.getCount()));
    }

    private enum BackupImportResult {
        SUCCESS, FAILURE_VERSION_DOWNGRADE, FAILURE_UNKNOWN
    }

    private static class VerificationRequestResult {
        private final String password;
        private final Optional<String> fcmToken;
        private final Optional<IOException> exception;

        private VerificationRequestResult(String password, Optional<String> fcmToken, Optional<IOException> exception) {
            this.password = password;
            this.fcmToken = fcmToken;
            this.exception = exception;
        }
    }

    private static class RegistrationState {
        private final State state;
        private final String e164number;
        private final String password;
        private final Optional<String> gcmToken;
        private final Optional<String> captchaToken;

        RegistrationState(State state, String e164number, String password, Optional<String> gcmToken, Optional<String> captchaToken) {
            this.state = state;
            this.e164number = e164number;
            this.password = password;
            this.gcmToken = gcmToken;
            this.captchaToken = captchaToken;
        }

        RegistrationState(State state, RegistrationState previous) {
            this.state = state;
            this.e164number = previous.e164number;
            this.password = previous.password;
            this.gcmToken = previous.gcmToken;
            this.captchaToken = previous.captchaToken;
        }

        RegistrationState(Optional<String> captchaToken, RegistrationState previous) {
            this.state = previous.state;
            this.e164number = previous.e164number;
            this.password = previous.password;
            this.gcmToken = previous.gcmToken;
            this.captchaToken = captchaToken;
        }

        private enum State {
            INITIAL, VERIFYING, CHECKING, PIN
        }
    }

    private class SmsRetrieverReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "SmsRetrieverReceiver received a broadcast...");

            if (SmsRetriever.SMS_RETRIEVED_ACTION.equals(intent.getAction())) {
                Bundle extras = intent.getExtras();
                Status status = (Status) extras.get(SmsRetriever.EXTRA_STATUS);

                switch (status.getStatusCode()) {
                    case CommonStatusCodes.SUCCESS:
                        Optional<String> code = VerificationCodeParser.parse(context, (String) extras.get(SmsRetriever.EXTRA_SMS_MESSAGE));
                        if (code.isPresent()) {
                            Log.i(TAG, "Received verification code.");
                            handleVerificationCodeReceived(code.get());
                        } else {
                            Log.w(TAG, "Could not parse verification code.");
                        }
                        break;
                    case CommonStatusCodes.TIMEOUT:
                        Log.w(TAG, "Hit a timeout waiting for the SMS to arrive.");
                        break;
                }
            } else {
                Log.w(TAG, "SmsRetrieverReceiver received the wrong action?");
            }
        }
    }

    private class CountryCodeChangedListener implements TextWatcher {
        @Override
        public void afterTextChanged(Editable s) {
            if (TextUtils.isEmpty(s) || !TextUtils.isDigitsOnly(s)) {
                setCountryDisplay(getString(R.string.RegistrationActivity_select_your_country));
                countryFormatter = null;
                return;
            }

            int countryCode = Integer.parseInt(s.toString());
            String regionCode = PhoneNumberUtil.getInstance().getRegionCodeForCountryCode(countryCode);

            setCountryFormatter(countryCode);
            setCountryDisplay(PhoneNumberFormatter.getRegionDisplayName(regionCode));

            if (!TextUtils.isEmpty(regionCode) && !regionCode.equals("ZZ")) {
                number.requestFocus();
            }
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }

    private class NumberChangedListener implements TextWatcher {

        @Override
        public void afterTextChanged(Editable s) {
            if (countryFormatter == null)
                return;

            if (TextUtils.isEmpty(s))
                return;

            countryFormatter.clear();

            String number = s.toString().replaceAll("[^\\d.]", "");
            String formattedNumber = null;

            for (int i = 0; i < number.length(); i++) {
                formattedNumber = countryFormatter.inputDigit(number.charAt(i));
            }

            if (formattedNumber != null && !s.toString().equals(formattedNumber)) {
                s.replace(0, s.length(), formattedNumber);
            }
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {

        }
    }
}
