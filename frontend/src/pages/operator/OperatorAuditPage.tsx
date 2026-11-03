import { useQuery } from '@tanstack/react-query';
import { OPERATOR_KEY, operatorApi } from '@/api/operator';
import { AuditLog } from '@/pages/settings/audit/AuditPage';

/** Audit log across all firmas for the operator; ?firma= narrows it to one (also the link from a firma card). */
export function OperatorAuditPage() {
  // Names for the firma column and filter (installations have far fewer than 200 firmas for now).
  const firmas = useQuery({ queryKey: [...OPERATOR_KEY, 'names'], queryFn: () => operatorApi.list('', 0, 200) });
  return <AuditLog basePath="/operator/audit" operator={{ firmas: firmas.data?.items ?? [] }} />;
}
