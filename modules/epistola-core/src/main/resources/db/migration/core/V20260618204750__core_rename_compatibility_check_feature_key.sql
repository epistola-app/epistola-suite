-- The compatibility-check feature's toggle/entitlement key was renamed
-- `support-upgrading` -> `support-compatibility-check`, aligning with the hub wire key
-- (SupportFeature.COMPATIBILITY_CHECK). Re-key any existing tenant toggle rows so they
-- keep applying. The new key cannot already exist, so no conflict.
UPDATE feature_toggles
SET feature_key = 'support-compatibility-check'
WHERE feature_key = 'support-upgrading';
