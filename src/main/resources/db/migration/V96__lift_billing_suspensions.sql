-- JIKU-254: an ended services subscription now pauses services instead of
-- suspending the whole organization. Organizations suspended by the old
-- billing sweep are reopened; a suspension the platform team decided (recorded
-- as TENANT_SUSPENDED in the admin audit log) stays in place.
UPDATE tenant t
SET status = 'ACTIVE'
WHERE t.status = 'SUSPENDED'
  AND EXISTS (
      SELECT 1 FROM subscription s
      WHERE s.tenant_id = t.id::text AND s.status = 'EXPIRED'
  )
  AND NOT EXISTS (
      SELECT 1 FROM admin_audit_log a
      WHERE a.target = 'tenant:' || t.id::text AND a.action = 'TENANT_SUSPENDED'
  );
