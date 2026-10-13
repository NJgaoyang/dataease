package io.dataease.enhance;

/**
 * Bridge between verified enhancement requests and the native DataEase request-scoped identity.
 *
 * The core-backend module supplies the implementation so the addon never imports
 * licensed/native internals or guesses their token format.
 */
public interface EnhanceNativeIdentity {
    /**
     * Install the validated user and return a scope that restores the previous native identity.
     * Call only around synchronous request processing.
     */
    Scope enter(EnhanceSession.Identity principal);

    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}