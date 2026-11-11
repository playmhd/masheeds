package com.masheeds.player;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;
import android.util.Base64;

import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class GoogleSignIn {
    record Token(String idToken, String nonce) {}
    record Result(Token token, boolean cancelled, boolean noCredential) {}

    private GoogleSignIn() {}

    static void request(Activity activity, CancellationSignal cancellation, Executor callbackExecutor,
                        Consumer<Result> callback) {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String nonce = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .setNonce(hash(nonce))
                .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        CredentialManager.create(activity).getCredentialAsync(activity, request, cancellation, callbackExecutor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override public void onResult(GetCredentialResponse response) {
                        callback.accept(new Result(readToken(response.getCredential(), nonce), false, false));
                    }

                    @Override public void onError(GetCredentialException error) {
                        callback.accept(new Result(null, error instanceof GetCredentialCancellationException,
                                error instanceof NoCredentialException));
                    }
                });
    }

    static void clear(Context context, Executor callbackExecutor) {
        CredentialManager.create(context).clearCredentialStateAsync(new ClearCredentialStateRequest(), null,
                callbackExecutor, new CredentialManagerCallback<Void, ClearCredentialException>() {
                    @Override public void onResult(Void ignored) {}
                    @Override public void onError(ClearCredentialException ignored) {}
                });
    }

    private static Token readToken(Credential credential, String nonce) {
        if (credential instanceof CustomCredential custom
                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(custom.getType())) {
            try {
                return new Token(GoogleIdTokenCredential.createFrom(custom.getData()).getIdToken(), nonce);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String hash(String nonce) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(nonce.getBytes(StandardCharsets.UTF_8));
            return String.format(Locale.ROOT, "%064x", new BigInteger(1, digest));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
