Debug-only keystore, committed on purpose so `adb install` works without setup.
It is NOT a secret and must never sign a real release.

Release signing uses the environment:
  TESSL_KEYSTORE, TESSL_STORE_PASS, TESSL_KEY_ALIAS, TESSL_KEY_PASS
