package com.masheeds.player;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CancellationSignal;

import com.masheeds.shared.ReviewerSignIn;

/** Runs the existing Google OAuth client under the Android package it was registered for. */
public final class ReviewerSignInActivity extends Activity {
    private final CancellationSignal pending = new CancellationSignal();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!trustedCaller() || !DiscoverBackend.signInConfigured()) {
            fail();
            return;
        }
        try {
            GoogleSignIn.request(this, pending, this::runOnUiThread, result -> {
                if (isDestroyed() || isFinishing()) return;
                if (result.token() != null && trustedCaller()) {
                    // A fresh Google assertion goes only to the signed caller via the activity result.
                    // No Masheeds session or refresh token is shared.
                    setResult(RESULT_OK, new Intent()
                            .putExtra(ReviewerSignIn.ID_TOKEN, result.token().idToken())
                            .putExtra(ReviewerSignIn.NONCE, result.token().nonce()));
                    finish();
                } else if (result.cancelled()) {
                    finish();
                } else {
                    fail();
                }
            });
        } catch (RuntimeException ignored) {
            fail();
        }
    }

    private boolean trustedCaller() {
        return ReviewerSignIn.REVIEWER.equals(getCallingPackage())
                && getPackageManager().checkSignatures(getPackageName(), getCallingPackage())
                == PackageManager.SIGNATURE_MATCH;
    }

    private void fail() {
        setResult(RESULT_FIRST_USER);
        finish();
    }

    @Override protected void onDestroy() {
        pending.cancel();
        super.onDestroy();
    }
}
