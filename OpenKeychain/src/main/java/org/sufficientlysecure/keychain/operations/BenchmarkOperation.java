/*
 * Copyright (C) 2015 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2015 Vincent Breitmoser <v.breitmoser@mugenguild.com>
 * Copyright (C) 2015 Adithya Abraham Philip <adithyaphilip@gmail.com>
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

package org.sufficientlysecure.keychain.operations;


import java.util.Random;

import android.content.Context;
import android.support.annotation.NonNull;

import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.S2K;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.operator.PBEDataDecryptorFactory;
import org.bouncycastle.openpgp.operator.PGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBEDataDecryptorFactoryBuilder;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.operations.results.BenchmarkResult;
import org.sufficientlysecure.keychain.operations.results.DecryptVerifyResult;
import org.sufficientlysecure.keychain.operations.results.OperationResult.LogType;
import org.sufficientlysecure.keychain.operations.results.OperationResult.OperationLog;
import org.sufficientlysecure.keychain.operations.results.SignEncryptResult;
import org.sufficientlysecure.keychain.pgp.PgpDecryptVerifyInputParcel;
import org.sufficientlysecure.keychain.pgp.PgpDecryptVerifyOperation;
import org.sufficientlysecure.keychain.pgp.PgpSecurityConstants;
import org.sufficientlysecure.keychain.pgp.PgpSecurityConstants.OpenKeychainSymmetricKeyAlgorithmTags;
import org.sufficientlysecure.keychain.pgp.Progressable;
import org.sufficientlysecure.keychain.pgp.SignEncryptParcel;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.BenchmarkInputParcel;
import org.sufficientlysecure.keychain.service.input.CryptoInputParcel;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.Passphrase;
import org.sufficientlysecure.keychain.util.ProgressScaler;


public class BenchmarkOperation extends BaseOperation<BenchmarkInputParcel> {

    public BenchmarkOperation(Context context, ProviderHelper providerHelper, Progressable
            progressable) {
        super(context, providerHelper, progressable);
    }

    @NonNull
    @Override
    public BenchmarkResult execute(BenchmarkInputParcel consolidateInputParcel,
                                     CryptoInputParcel cryptoInputParcel) {
        OperationLog log = new OperationLog();
        log.add(LogType.MSG_BENCH, 0);

        // random data
        byte[] buf = new byte[1024*1024*10];
        new Random().nextBytes(buf);

        Passphrase passphrase = new Passphrase("a");

        int numRepeats = 5;
        long totalTime = 0;

        // encrypt
        SignEncryptResult encryptResult;
        int i = 0;
        do {
            SignEncryptOperation op =
                    new SignEncryptOperation(mContext, mProviderHelper,
                            new ProgressScaler(mProgressable, i*(50/numRepeats), (i+1)*(50/numRepeats), 100), mCancelled);
            SignEncryptParcel input = new SignEncryptParcel();
            input.setSymmetricPassphrase(passphrase);
            input.setSymmetricEncryptionAlgorithm(OpenKeychainSymmetricKeyAlgorithmTags.AES_128);
            input.setBytes(buf);
            encryptResult = op.execute(input, new CryptoInputParcel());
            log.add(encryptResult, 1);
            log.add(LogType.MSG_BENCH_ENC_TIME, 2,
                    String.format("%.2f", encryptResult.getResults().get(0).mOperationTime / 1000.0));
            totalTime += encryptResult.getResults().get(0).mOperationTime;
        } while (++i < numRepeats);

        long encryptionTime = totalTime / numRepeats;
        totalTime = 0;

        // decrypt
        i = 0;
        do {
            DecryptVerifyResult decryptResult;
            PgpDecryptVerifyOperation op =
                    new PgpDecryptVerifyOperation(mContext, mProviderHelper,
                            new ProgressScaler(mProgressable, 50 +i*(50/numRepeats), 50 +(i+1)*(50/numRepeats), 100));
            PgpDecryptVerifyInputParcel input = new PgpDecryptVerifyInputParcel(encryptResult.getResultBytes());
            input.setAllowSymmetricDecryption(true);
            decryptResult = op.execute(input, new CryptoInputParcel(passphrase));
            log.add(decryptResult, 1);
            log.add(LogType.MSG_BENCH_DEC_TIME, 2, String.format("%.2f", decryptResult.mOperationTime / 1000.0));
            totalTime += decryptResult.mOperationTime;
        } while (++i < numRepeats);

        long decryptionTime = totalTime / numRepeats;
        totalTime = 0;

        int iterationsFor100ms;
        try {
            PGPDigestCalculatorProvider digestCalcProvider = new JcaPGPDigestCalculatorProviderBuilder()
                    .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build();
            PBEDataDecryptorFactory decryptorFactory = new JcePBEDataDecryptorFactoryBuilder(
                    digestCalcProvider).setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                    "".toCharArray());

            byte[] iv = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
            int iterations = 0;
            while (iterations < 255 && totalTime < 100) {
                iterations += 1;

                S2K s2k = new S2K(HashAlgorithmTags.SHA1, iv, iterations);
                totalTime = System.currentTimeMillis();
                decryptorFactory.makeKeyFromPassPhrase(SymmetricKeyAlgorithmTags.AES_128, s2k);
                totalTime = System.currentTimeMillis() -totalTime;

                if ((iterations % 10) == 0) {
                    log.add(LogType.MSG_BENCH_S2K_FOR_IT, 1, Integer.toString(iterations), Long.toString(totalTime));
                }

            }
            iterationsFor100ms = iterations;

        } catch (PGPException e) {
            Log.e(Constants.TAG, "internal error during benchmark", e);
            log.add(LogType.MSG_INTERNAL_ERROR, 0);
            return new BenchmarkResult(BenchmarkResult.RESULT_ERROR, log);
        }

        log.add(LogType.MSG_BENCH_S2K_100MS_ITS, 1, Integer.toString(iterationsFor100ms));
        log.add(LogType.MSG_BENCH_ENC_TIME_AVG, 1, String.format("%.2f", encryptionTime/1000.0));
        log.add(LogType.MSG_BENCH_DEC_TIME_AVG, 1, String.format("%.2f", decryptionTime/1000.0));

        log.add(LogType.MSG_BENCH_SUCCESS, 0);
        return new BenchmarkResult(BenchmarkResult.RESULT_OK, log);
    }

}
