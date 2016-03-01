/*
 * Copyright (C) 2013-2015 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2015 Vincent Breitmoser <v.breitmoser@mugenguild.com>
 * Copyright (C) 2013-2014 Signe Rüsch
 * Copyright (C) 2013-2014 Philipp Jakubeit
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.ui;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ViewAnimator;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.pgp.CanonicalizedSecretKey;
import org.sufficientlysecure.keychain.pgp.CanonicalizedSecretKeyRing;
import org.sufficientlysecure.keychain.provider.KeychainContract;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.PassphraseCacheService;
import org.sufficientlysecure.keychain.service.input.CryptoInputParcel;
import org.sufficientlysecure.keychain.service.input.RequiredInputParcel;
import org.sufficientlysecure.keychain.ui.base.BaseSecurityTokenNfcActivity;
import org.sufficientlysecure.keychain.ui.util.ThemeChanger;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.OrientationUtils;
import org.sufficientlysecure.keychain.util.Passphrase;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import nordpol.android.NfcGuideView;

/**
 * This class provides a communication interface to OpenPGP applications on ISO SmartCard compliant
 * NFC devices.
 * For the full specs, see http://g10code.com/docs/openpgp-card-2.0.pdf
 */
public class SecurityTokenOperationActivity extends BaseSecurityTokenNfcActivity {

    public static final String EXTRA_REQUIRED_INPUT = "required_input";
    public static final String EXTRA_CRYPTO_INPUT = "crypto_input";

    public static final String RESULT_CRYPTO_INPUT = "result_data";

    public ViewAnimator vAnimator;
    public TextView vErrorText;
    public Button vErrorTryAgainButton;
    public NfcGuideView nfcGuideView;

    private RequiredInputParcel mRequiredInput;

    private static final byte[] BLANK_FINGERPRINT = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    private CryptoInputParcel mInputParcel;

    @Override
    protected void initTheme() {
        mThemeChanger = new ThemeChanger(this);
        mThemeChanger.setThemes(R.style.Theme_Keychain_Light_Dialog,
                R.style.Theme_Keychain_Dark_Dialog);
        mThemeChanger.changeTheme();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(Constants.TAG, "NfcOperationActivity.onCreate");

        nfcGuideView = (NfcGuideView) findViewById(R.id.nfc_guide_view);

        // prevent annoying orientation changes while fumbling with the device
        OrientationUtils.lockOrientation(this);
        // prevent close when touching outside of the dialog (happens easily when fumbling with the device)
        setFinishOnTouchOutside(false);
        // keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mInputParcel = getIntent().getParcelableExtra(EXTRA_CRYPTO_INPUT);

        setTitle(R.string.security_token_nfc_text);

        vAnimator = (ViewAnimator) findViewById(R.id.view_animator);
        vAnimator.setDisplayedChild(0);

        nfcGuideView.setCurrentStatus(NfcGuideView.NfcGuideViewStatus.STARTING_POSITION);

        vErrorText = (TextView) findViewById(R.id.security_token_activity_3_error_text);
        vErrorTryAgainButton = (Button) findViewById(R.id.security_token_activity_3_error_try_again);
        vErrorTryAgainButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resumeTagHandling();

                obtainPassphraseIfRequired();
                vAnimator.setDisplayedChild(0);

                nfcGuideView.setVisibility(View.VISIBLE);
                nfcGuideView.setCurrentStatus(NfcGuideView.NfcGuideViewStatus.STARTING_POSITION);
            }
        });
        Button vCancel = (Button) findViewById(R.id.security_token_activity_0_cancel);
        vCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        Intent intent = getIntent();
        Bundle data = intent.getExtras();

        mRequiredInput = data.getParcelable(EXTRA_REQUIRED_INPUT);

        obtainPassphraseIfRequired();
    }

    private void obtainPassphraseIfRequired() {
        // obtain passphrase for this subkey
        if (mRequiredInput.mType != RequiredInputParcel.RequiredInputType.NFC_MOVE_KEY_TO_CARD
                && mRequiredInput.mType != RequiredInputParcel.RequiredInputType.NFC_RESET_CARD) {
            obtainSecurityTokenPin(mRequiredInput);
        }
    }

    @Override
    protected void initLayout() {
        setContentView(R.layout.security_token_operation_activity);
    }

    @Override
    public void onNfcPreExecute() {
        // start with indeterminate progress
        vAnimator.setDisplayedChild(1);
        nfcGuideView.setCurrentStatus(NfcGuideView.NfcGuideViewStatus.TRANSFERRING);
    }

    @Override
    protected void doNfcInBackground() throws IOException {

        switch (mRequiredInput.mType) {
            case NFC_DECRYPT: {
                for (int i = 0; i < mRequiredInput.mInputData.length; i++) {
                    byte[] encryptedSessionKey = mRequiredInput.mInputData[i];
                    byte[] decryptedSessionKey = nfcDecryptSessionKey(encryptedSessionKey);
                    mInputParcel.addCryptoData(encryptedSessionKey, decryptedSessionKey);
                }
                break;
            }
            case NFC_SIGN: {
                mInputParcel.addSignatureTime(mRequiredInput.mSignatureTime);

                for (int i = 0; i < mRequiredInput.mInputData.length; i++) {
                    byte[] hash = mRequiredInput.mInputData[i];
                    int algo = mRequiredInput.mSignAlgos[i];
                    byte[] signedHash = nfcCalculateSignature(hash, algo);
                    mInputParcel.addCryptoData(hash, signedHash);
                }
                break;
            }
            case NFC_MOVE_KEY_TO_CARD: {
                // TODO: assume PIN and Admin PIN to be default for this operation
                mPin = new Passphrase("123456");
                mAdminPin = new Passphrase("12345678");

                ProviderHelper providerHelper = new ProviderHelper(this);
                CanonicalizedSecretKeyRing secretKeyRing;
                try {
                    secretKeyRing = providerHelper.getCanonicalizedSecretKeyRing(
                            KeychainContract.KeyRings.buildUnifiedKeyRingsFindBySubkeyUri(mRequiredInput.getMasterKeyId())
                    );
                } catch (ProviderHelper.NotFoundException e) {
                    throw new IOException("Couldn't find subkey for key to token operation.");
                }

                byte[] newPin = mRequiredInput.mInputData[0];
                byte[] newAdminPin = mRequiredInput.mInputData[1];

                for (int i = 2; i < mRequiredInput.mInputData.length; i++) {
                    byte[] subkeyBytes = mRequiredInput.mInputData[i];
                    ByteBuffer buf = ByteBuffer.wrap(subkeyBytes);
                    long subkeyId = buf.getLong();

                    CanonicalizedSecretKey key = secretKeyRing.getSecretKey(subkeyId);

                    long keyGenerationTimestampMillis = key.getCreationTime().getTime();
                    long keyGenerationTimestamp = keyGenerationTimestampMillis / 1000;
                    byte[] timestampBytes = ByteBuffer.allocate(4).putInt((int) keyGenerationTimestamp).array();
                    byte[] tokenSerialNumber = Arrays.copyOf(nfcGetAid(), 16);

                    Passphrase passphrase;
                    try {
                        passphrase = PassphraseCacheService.getCachedPassphrase(this,
                                mRequiredInput.getMasterKeyId(), mRequiredInput.getSubKeyId());
                    } catch (PassphraseCacheService.KeyNotFoundException e) {
                        throw new IOException("Unable to get cached passphrase!");
                    }

                    if (key.canSign() || key.canCertify()) {
                        if (shouldPutKey(key.getFingerprint(), 0)) {
                            nfcPutKey(0xB6, key, passphrase);
                            nfcPutData(0xCE, timestampBytes);
                            nfcPutData(0xC7, key.getFingerprint());
                        } else {
                            throw new IOException("Key slot occupied; token must be reset to put new signature key.");
                        }
                    } else if (key.canEncrypt()) {
                        if (shouldPutKey(key.getFingerprint(), 1)) {
                            nfcPutKey(0xB8, key, passphrase);
                            nfcPutData(0xCF, timestampBytes);
                            nfcPutData(0xC8, key.getFingerprint());
                        } else {
                            throw new IOException("Key slot occupied; token must be reset to put new decryption key.");
                        }
                    } else if (key.canAuthenticate()) {
                        if (shouldPutKey(key.getFingerprint(), 2)) {
                            nfcPutKey(0xA4, key, passphrase);
                            nfcPutData(0xD0, timestampBytes);
                            nfcPutData(0xC9, key.getFingerprint());
                        } else {
                            throw new IOException("Key slot occupied; token must be reset to put new authentication key.");
                        }
                    } else {
                        throw new IOException("Inappropriate key flags for Security Token key.");
                    }

                    // TODO: Is this really used anywhere?
                    mInputParcel.addCryptoData(subkeyBytes, tokenSerialNumber);
                }

                // change PINs afterwards
                nfcModifyPIN(0x81, newPin);
                nfcModifyPIN(0x83, newAdminPin);

                break;
            }
            case NFC_RESET_CARD: {
                nfcResetCard();

                break;
            }
            default: {
                throw new AssertionError("Unhandled mRequiredInput.mType");
            }
        }

    }

    @Override
    protected final void onNfcPostExecute() {
        handleResult(mInputParcel);

        // show finish
        vAnimator.setDisplayedChild(2);

        nfcGuideView.setCurrentStatus(NfcGuideView.NfcGuideViewStatus.DONE);

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                // check all 200ms if Security Token has been taken away
                while (true) {
                    if (isNfcConnected()) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ignored) {
                        }
                    } else {
                        return null;
                    }
                }
            }

            @Override
            protected void onPostExecute(Void result) {
                super.onPostExecute(result);
                finish();
            }
        }.execute();
    }

    /**
     * Defines how the result of this activity is returned.
     * Is overwritten in RemoteSecurityTokenOperationActivity
     */
    protected void handleResult(CryptoInputParcel inputParcel) {
        Intent result = new Intent();
        // send back the CryptoInputParcel we received
        result.putExtra(RESULT_CRYPTO_INPUT, inputParcel);
        setResult(RESULT_OK, result);
    }

    @Override
    protected void onNfcError(String error) {
        pauseTagHandling();

        vErrorText.setText(error + "\n\n" + getString(R.string.security_token_nfc_try_again_text));
        vAnimator.setDisplayedChild(3);

        nfcGuideView.setVisibility(View.GONE);
    }

    @Override
    public void onNfcPinError(String error) {
        onNfcError(error);

        // clear (invalid) passphrase
        PassphraseCacheService.clearCachedPassphrase(
                this, mRequiredInput.getMasterKeyId(), mRequiredInput.getSubKeyId());
    }

    private boolean shouldPutKey(byte[] fingerprint, int idx) throws IOException {
        byte[] tokenFingerprint = nfcGetMasterKeyFingerprint(idx);

        // Note: special case: This should not happen, but happens with
        // https://github.com/FluffyKaon/OpenPGP-Card, thus for now assume true
        if (tokenFingerprint == null) {
            return true;
        }

        // Slot is empty, or contains this key already. PUT KEY operation is safe
        if (Arrays.equals(tokenFingerprint, BLANK_FINGERPRINT) ||
                Arrays.equals(tokenFingerprint, fingerprint)) {
            return true;
        }

        // Slot already contains a different key; don't overwrite it.
        return false;
    }

}
