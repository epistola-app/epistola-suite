ALTER INDEX public.audit_log_pkey ATTACH PARTITION public.audit_log_202606_pkey;

ALTER INDEX public.document_content_pkey ATTACH PARTITION public.document_content_2026_06_pkey;

ALTER INDEX public.document_generation_requests_pkey ATTACH PARTITION public.document_generation_requests_202606_pkey;

ALTER INDEX public.documents_pkey ATTACH PARTITION public.documents_202606_pkey;

ALTER INDEX public.event_log_pkey ATTACH PARTITION public.event_log_<month>_pkey;

ALTER INDEX public.event_log_pkey ATTACH PARTITION public.event_log_<month>_pkey;

ALTER INDEX public.generation_results_pkey ATTACH PARTITION public.generation_results_<month>_pkey;

ALTER INDEX public.generation_results_pkey ATTACH PARTITION public.generation_results_<month>_pkey;

ALTER INDEX public.idx_audit_log_action_occurred ATTACH PARTITION public.audit_log_202606_action_occurred_at_idx;

ALTER INDEX public.idx_audit_log_actor_occurred ATTACH PARTITION public.audit_log_202606_actor_user_id_occurred_at_idx;

ALTER INDEX public.idx_audit_log_tenant_occurred ATTACH PARTITION public.audit_log_202606_tenant_key_occurred_at_idx;

ALTER INDEX public.idx_dgr_batch_status ATTACH PARTITION public.document_generation_requests_202606_batch_id_status_idx;

ALTER INDEX public.idx_dgr_pending_poll ATTACH PARTITION public.document_generation_requests_202606_status_created_at_idx;

ALTER INDEX public.idx_documents_correlation_id ATTACH PARTITION public.documents_202606_tenant_key_correlation_id_idx;

ALTER INDEX public.idx_documents_created_at ATTACH PARTITION public.documents_202606_created_at_idx;

ALTER INDEX public.idx_documents_template_key ATTACH PARTITION public.documents_202606_tenant_key_template_key_idx;

ALTER INDEX public.idx_documents_template_resource_id ATTACH PARTITION public.documents_202606_template_resource_id_idx;

ALTER INDEX public.idx_event_log_tenant_occurred ATTACH PARTITION public.event_log_<month>_tenant_key_occurred_at_idx;

ALTER INDEX public.idx_event_log_tenant_occurred ATTACH PARTITION public.event_log_<month>_tenant_key_occurred_at_idx;

ALTER INDEX public.idx_event_log_type ATTACH PARTITION public.event_log_<month>_event_type_idx;

ALTER INDEX public.idx_event_log_type ATTACH PARTITION public.event_log_<month>_event_type_idx;

ALTER INDEX public.idx_generation_requests_batch_key ATTACH PARTITION public.document_generation_requests_202606_batch_id_idx;

ALTER INDEX public.idx_generation_requests_correlation_id ATTACH PARTITION public.document_generation_requests_2026_tenant_key_correlation_id_idx;

ALTER INDEX public.idx_generation_requests_created_at ATTACH PARTITION public.document_generation_requests_202606_created_at_idx;

ALTER INDEX public.idx_generation_requests_document_key ATTACH PARTITION public.document_generation_requests_202606_document_key_idx;

ALTER INDEX public.idx_generation_requests_expires_at ATTACH PARTITION public.document_generation_requests_202606_expires_at_idx;

ALTER INDEX public.idx_generation_requests_status ATTACH PARTITION public.document_generation_requests_202606_status_idx;

ALTER INDEX public.idx_generation_requests_template_key ATTACH PARTITION public.document_generation_requests_202606_tenant_key_template_key_idx;

ALTER INDEX public.idx_generation_requests_template_resource_id ATTACH PARTITION public.document_generation_requests_202606_template_resource_id_idx;

ALTER INDEX public.idx_generation_results_partition_sequence ATTACH PARTITION public.generation_results_<month>_partition_sequence_idx;

ALTER INDEX public.idx_generation_results_partition_sequence ATTACH PARTITION public.generation_results_<month>_partition_sequence_idx;

ALTER INDEX public.idx_generation_results_tenant_key ATTACH PARTITION public.generation_results_<month>_tenant_key_idx;

ALTER INDEX public.idx_generation_results_tenant_key ATTACH PARTITION public.generation_results_<month>_tenant_key_idx;

ALTER SEQUENCE public.generation_results_sequence_seq OWNED BY public.generation_results.sequence;

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_key_hash_key UNIQUE (key_hash);

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_revoked_by_fkey FOREIGN KEY (revoked_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.app_metadata
    ADD CONSTRAINT app_metadata_pkey PRIMARY KEY (key);

ALTER TABLE ONLY public.application_log
    ADD CONSTRAINT application_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.asset_content
    ADD CONSTRAINT asset_content_pkey PRIMARY KEY (content_hash);

ALTER TABLE ONLY public.asset_types
    ADD CONSTRAINT asset_types_pkey PRIMARY KEY (media_type);

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (occurred_at, id);

ALTER TABLE ONLY public.audit_log ATTACH PARTITION public.audit_log_202606 FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');

ALTER TABLE ONLY public.audit_log_202606
    ADD CONSTRAINT audit_log_202606_pkey PRIMARY KEY (occurred_at, id);

ALTER TABLE ONLY public.auth_providers
    ADD CONSTRAINT auth_providers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.catalog_draft_merges
    ADD CONSTRAINT catalog_draft_merges_merged_by_fkey FOREIGN KEY (merged_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.catalog_draft_merges
    ADD CONSTRAINT catalog_draft_merges_pkey PRIMARY KEY (tenant_key, version_id, release_version);

ALTER TABLE ONLY public.catalog_draft_merges
    ADD CONSTRAINT catalog_draft_merges_tenant_key_version_id_fkey FOREIGN KEY (tenant_key, version_id) REFERENCES public.catalog_versions(tenant_key, version_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.catalog_exchange_bindings
    ADD CONSTRAINT catalog_exchange_bindings_pkey PRIMARY KEY (tenant_key, namespace, catalog_key);

ALTER TABLE ONLY public.catalog_exchange_bindings
    ADD CONSTRAINT catalog_exchange_bindings_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.catalog_release_merges
    ADD CONSTRAINT catalog_release_merges_pkey PRIMARY KEY (tenant_key, version_id, merged_version);

ALTER TABLE ONLY public.catalog_release_merges
    ADD CONSTRAINT fk_catalog_release_merges_version FOREIGN KEY (tenant_key, version_id) REFERENCES public.catalog_versions(tenant_key, version_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.catalog_release_publication_archives
    ADD CONSTRAINT catalog_release_publication_archives_pkey PRIMARY KEY (publication_id);

ALTER TABLE ONLY public.catalog_release_publication_archives
    ADD CONSTRAINT catalog_release_publication_archives_publication_id_fkey FOREIGN KEY (publication_id) REFERENCES public.catalog_release_publications(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.catalog_release_publications
    ADD CONSTRAINT catalog_release_publications_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.catalog_release_publications
    ADD CONSTRAINT fk_catalog_release_publications_version FOREIGN KEY (tenant_key, catalog_id, version) REFERENCES public.catalog_versions(tenant_key, catalog_id, version) ON DELETE CASCADE;

ALTER TABLE ONLY public.catalog_release_publications
    ADD CONSTRAINT uq_catalog_release_publications_release UNIQUE (tenant_key, catalog_id, version);

ALTER TABLE ONLY public.catalog_resource_types
    ADD CONSTRAINT catalog_resource_types_pkey PRIMARY KEY (resource_type);

ALTER TABLE ONLY public.catalog_resources
    ADD CONSTRAINT catalog_resources_pkey PRIMARY KEY (tenant_key, resource_id);

ALTER TABLE ONLY public.catalog_resources
    ADD CONSTRAINT fk_catalog_resources_catalog FOREIGN KEY (tenant_key, catalog_id) REFERENCES public.catalogs(tenant_key, catalog_id) ON DELETE CASCADE DEFERRABLE;

ALTER TABLE ONLY public.catalog_resources
    ADD CONSTRAINT fk_catalog_resources_type FOREIGN KEY (resource_type) REFERENCES public.catalog_resource_types(resource_type);

ALTER TABLE ONLY public.catalog_resources
    ADD CONSTRAINT uq_catalog_resources_address UNIQUE (tenant_key, resource_type, catalog_id, resource_key);

ALTER TABLE ONLY public.catalog_upstream_checks
    ADD CONSTRAINT catalog_upstream_checks_pkey PRIMARY KEY (tenant_key, catalog_id);

ALTER TABLE ONLY public.catalog_upstream_checks
    ADD CONSTRAINT fk_catalog_upstream_checks_catalog FOREIGN KEY (tenant_key, catalog_id) REFERENCES public.catalogs(tenant_key, catalog_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.catalog_version_dependencies
    ADD CONSTRAINT catalog_version_dependencies_pkey PRIMARY KEY (tenant_key, version_id, registry, namespace, dependency_key);

ALTER TABLE ONLY public.catalog_version_dependencies
    ADD CONSTRAINT fk_catalog_version_dependencies_catalog FOREIGN KEY (tenant_key, catalog_id) REFERENCES public.catalogs(tenant_key, catalog_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.catalog_version_dependencies
    ADD CONSTRAINT fk_catalog_version_dependencies_target FOREIGN KEY (tenant_key, target_version_id) REFERENCES public.catalog_versions(tenant_key, version_id) ON DELETE SET NULL (target_version_id);

ALTER TABLE ONLY public.catalog_version_dependencies
    ADD CONSTRAINT fk_catalog_version_dependencies_version FOREIGN KEY (tenant_key, version_id, catalog_id) REFERENCES public.catalog_versions(tenant_key, version_id, catalog_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.catalog_version_dependencies
    ADD CONSTRAINT uq_catalog_version_dependencies_alias UNIQUE (tenant_key, version_id, alias);

ALTER TABLE ONLY public.catalog_versions
    ADD CONSTRAINT catalog_versions_pkey PRIMARY KEY (tenant_key, version_id);

ALTER TABLE ONLY public.catalog_versions
    ADD CONSTRAINT catalog_versions_tenant_key_catalog_id_fkey FOREIGN KEY (tenant_key, catalog_id) REFERENCES public.catalogs(tenant_key, catalog_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.catalog_versions
    ADD CONSTRAINT fk_catalog_versions_base FOREIGN KEY (tenant_key, base_version_id) REFERENCES public.catalog_versions(tenant_key, version_id) ON DELETE SET NULL (base_version_id);

ALTER TABLE ONLY public.catalog_versions
    ADD CONSTRAINT fk_catalog_versions_default_theme FOREIGN KEY (tenant_key, default_theme_resource_id) REFERENCES public.catalog_resources(tenant_key, resource_id);

ALTER TABLE ONLY public.catalog_versions
    ADD CONSTRAINT uq_catalog_versions_catalog UNIQUE (tenant_key, version_id, catalog_id);

ALTER TABLE ONLY public.catalog_versions
    ADD CONSTRAINT uq_catalog_versions_key UNIQUE (tenant_key, catalog_id, version_key);

ALTER TABLE ONLY public.catalog_versions
    ADD CONSTRAINT uq_catalog_versions_number UNIQUE (tenant_key, catalog_id, version);

ALTER TABLE ONLY public.catalogs
    ADD CONSTRAINT catalogs_pkey PRIMARY KEY (tenant_key, catalog_id);

ALTER TABLE ONLY public.catalogs
    ADD CONSTRAINT catalogs_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.catalogs
    ADD CONSTRAINT fk_catalogs_next_version FOREIGN KEY (tenant_key, next_version_id) REFERENCES public.catalog_versions(tenant_key, version_id) ON DELETE SET NULL (next_version_id);

ALTER TABLE ONLY public.catalogs
    ADD CONSTRAINT uq_catalogs_name UNIQUE (tenant_key, registry, namespace, catalog_key);

ALTER TABLE ONLY public.catalogs
    ADD CONSTRAINT uq_catalogs_namespace_key UNIQUE (tenant_key, namespace, catalog_key);

ALTER TABLE ONLY public.changelog_acknowledgments
    ADD CONSTRAINT changelog_acknowledgments_pkey PRIMARY KEY (user_id);

ALTER TABLE ONLY public.changelog_acknowledgments
    ADD CONSTRAINT changelog_acknowledgments_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cluster_nodes
    ADD CONSTRAINT cluster_nodes_pkey PRIMARY KEY (node_id);

ALTER TABLE ONLY public.cluster_scheduled_task_registrations
    ADD CONSTRAINT cluster_scheduled_task_registrations_pkey PRIMARY KEY (task_key, node_id);

ALTER TABLE ONLY public.cluster_scheduled_task_registrations
    ADD CONSTRAINT cluster_scheduled_task_registrations_task_key_fkey FOREIGN KEY (task_key) REFERENCES public.cluster_tasks_scheduled(task_key) ON DELETE CASCADE;

ALTER TABLE ONLY public.cluster_tasks_scheduled
    ADD CONSTRAINT cluster_tasks_scheduled_pkey PRIMARY KEY (task_key);

ALTER TABLE ONLY public.cluster_tasks_scheduled
    ADD CONSTRAINT cluster_tasks_scheduled_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cluster_tasks_scheduled_node_state
    ADD CONSTRAINT cluster_tasks_scheduled_node_state_pkey PRIMARY KEY (task_key, node_id);

ALTER TABLE ONLY public.cluster_tasks_scheduled_node_state
    ADD CONSTRAINT cluster_tasks_scheduled_node_state_task_key_fkey FOREIGN KEY (task_key) REFERENCES public.cluster_tasks_scheduled(task_key) ON DELETE CASCADE;

ALTER TABLE ONLY public.cluster_timers
    ADD CONSTRAINT cluster_timers_pkey PRIMARY KEY (timer_key);

ALTER TABLE ONLY public.cluster_timers
    ADD CONSTRAINT cluster_timers_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.code_list_refreshes
    ADD CONSTRAINT code_list_refreshes_pkey PRIMARY KEY (tenant_key, version_id, resource_id);

ALTER TABLE ONLY public.code_list_refreshes
    ADD CONSTRAINT code_list_refreshes_tenant_key_version_id_fkey FOREIGN KEY (tenant_key, version_id) REFERENCES public.catalog_versions(tenant_key, version_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.consumer_node_assignments
    ADD CONSTRAINT consumer_node_assignments_pkey PRIMARY KEY (tenant_key, consumer_id, node_id);

ALTER TABLE ONLY public.consumer_partition_cursors
    ADD CONSTRAINT consumer_partition_cursors_pkey PRIMARY KEY (tenant_key, consumer_id, partition);

ALTER TABLE ONLY public.document_content
    ADD CONSTRAINT document_content_pkey PRIMARY KEY (key, created_at);

ALTER TABLE ONLY public.document_content ATTACH PARTITION public.document_content_2026_06 FOR VALUES FROM ('2026-05-31 22:00:00+00') TO ('2026-06-30 22:00:00+00');

ALTER TABLE ONLY public.document_content_2026_06
    ADD CONSTRAINT document_content_2026_06_pkey PRIMARY KEY (key, created_at);

ALTER TABLE ONLY public.document_generation_batches
    ADD CONSTRAINT document_generation_batches_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.document_generation_batches
    ADD CONSTRAINT document_generation_batches_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.document_generation_requests
    ADD CONSTRAINT document_generation_requests_pkey PRIMARY KEY (id, created_at);

ALTER TABLE ONLY public.document_generation_requests ATTACH PARTITION public.document_generation_requests_202606 FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');

ALTER TABLE ONLY public.document_generation_requests_202606
    ADD CONSTRAINT document_generation_requests_202606_pkey PRIMARY KEY (id, created_at);

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_pkey PRIMARY KEY (id, created_at);

ALTER TABLE ONLY public.documents ATTACH PARTITION public.documents_202606 FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');

ALTER TABLE ONLY public.documents_202606
    ADD CONSTRAINT documents_202606_pkey PRIMARY KEY (id, created_at);

ALTER TABLE ONLY public.environment_catalog_deployments
    ADD CONSTRAINT environment_catalog_deployments_deployed_by_fkey FOREIGN KEY (deployed_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.environment_catalog_deployments
    ADD CONSTRAINT fk_environment_catalog_deployments_environment FOREIGN KEY (tenant_key, environment_key) REFERENCES public.environments(tenant_key, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.environment_catalog_deployments
    ADD CONSTRAINT fk_environment_catalog_deployments_version FOREIGN KEY (tenant_key, target_version_id, catalog_id) REFERENCES public.catalog_versions(tenant_key, version_id, catalog_id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY public.environment_catalog_deployments
    ADD CONSTRAINT pk_environment_catalog_deployments PRIMARY KEY (tenant_key, id);

ALTER TABLE ONLY public.environment_deployment_log
    ADD CONSTRAINT environment_deployment_log_changed_by_fkey FOREIGN KEY (changed_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.environment_deployment_log
    ADD CONSTRAINT fk_environment_deployment_log_environment FOREIGN KEY (tenant_key, environment_key) REFERENCES public.environments(tenant_key, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.environment_deployment_log
    ADD CONSTRAINT pk_environment_deployment_log PRIMARY KEY (tenant_key, id);

ALTER TABLE ONLY public.environments
    ADD CONSTRAINT environments_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.environments
    ADD CONSTRAINT environments_pkey PRIMARY KEY (tenant_key, id);

ALTER TABLE ONLY public.environments
    ADD CONSTRAINT environments_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.environments
    ADD CONSTRAINT environments_tenant_key_name_key UNIQUE (tenant_key, name);

ALTER TABLE ONLY public.environments
    ADD CONSTRAINT environments_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.event_log
    ADD CONSTRAINT event_log_pkey PRIMARY KEY (occurred_at, id);

ALTER TABLE ONLY public.event_log ATTACH PARTITION public.event_log_<month> FOR VALUES FROM (<from>) TO (<to>);

ALTER TABLE ONLY public.event_log ATTACH PARTITION public.event_log_<month> FOR VALUES FROM (<from>) TO (<to>);

ALTER TABLE ONLY public.event_log_<month>
    ADD CONSTRAINT event_log_<month>_pkey PRIMARY KEY (occurred_at, id);

ALTER TABLE ONLY public.event_log_<month>
    ADD CONSTRAINT event_log_<month>_pkey PRIMARY KEY (occurred_at, id);

ALTER TABLE ONLY public.exchange_oauth_authorizations
    ADD CONSTRAINT exchange_oauth_authorizations_pkey PRIMARY KEY (tenant_key);

ALTER TABLE ONLY public.exchange_oauth_authorizations
    ADD CONSTRAINT exchange_oauth_authorizations_state_hash_key UNIQUE (state_hash);

ALTER TABLE ONLY public.exchange_oauth_authorizations
    ADD CONSTRAINT exchange_oauth_authorizations_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.exchange_tenant_connections
    ADD CONSTRAINT exchange_tenant_connections_pkey PRIMARY KEY (tenant_key);

ALTER TABLE ONLY public.exchange_tenant_connections
    ADD CONSTRAINT exchange_tenant_connections_tenant_connection_id_key UNIQUE (tenant_connection_id);

ALTER TABLE ONLY public.exchange_tenant_connections
    ADD CONSTRAINT exchange_tenant_connections_tenant_connection_reference_key UNIQUE (tenant_connection_reference);

ALTER TABLE ONLY public.exchange_tenant_connections
    ADD CONSTRAINT exchange_tenant_connections_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feature_toggles
    ADD CONSTRAINT feature_toggles_pkey PRIMARY KEY (tenant_key, feature_key);

ALTER TABLE ONLY public.feature_toggles
    ADD CONSTRAINT feature_toggles_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT feedback_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT feedback_pkey PRIMARY KEY (tenant_key, id);

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT feedback_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_assets
    ADD CONSTRAINT feedback_assets_pkey PRIMARY KEY (tenant_key, feedback_id, id);

ALTER TABLE ONLY public.feedback_assets
    ADD CONSTRAINT feedback_assets_tenant_key_feedback_id_fkey FOREIGN KEY (tenant_key, feedback_id) REFERENCES public.feedback(tenant_key, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback_comments
    ADD CONSTRAINT feedback_comments_pkey PRIMARY KEY (tenant_key, feedback_id, id);

ALTER TABLE ONLY public.feedback_comments
    ADD CONSTRAINT feedback_comments_tenant_key_feedback_id_fkey FOREIGN KEY (tenant_key, feedback_id) REFERENCES public.feedback(tenant_key, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.generation_results
    ADD CONSTRAINT generation_results_pkey PRIMARY KEY (sequence, created_at);

ALTER TABLE ONLY public.generation_results ALTER COLUMN sequence SET DEFAULT nextval('public.generation_results_sequence_seq'::regclass);

ALTER TABLE ONLY public.generation_results ATTACH PARTITION public.generation_results_<month> FOR VALUES FROM (<from>) TO (<to>);

ALTER TABLE ONLY public.generation_results ATTACH PARTITION public.generation_results_<month> FOR VALUES FROM (<from>) TO (<to>);

ALTER TABLE ONLY public.generation_results_<month>
    ADD CONSTRAINT generation_results_<month>_pkey PRIMARY KEY (sequence, created_at);

ALTER TABLE ONLY public.generation_results_<month>
    ADD CONSTRAINT generation_results_<month>_pkey PRIMARY KEY (sequence, created_at);

ALTER TABLE ONLY public.load_test_runs
    ADD CONSTRAINT load_test_runs_batch_id_key UNIQUE (batch_id);

ALTER TABLE ONLY public.load_test_runs
    ADD CONSTRAINT load_test_runs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.load_test_runs
    ADD CONSTRAINT load_test_runs_template_fkey FOREIGN KEY (tenant_key, template_resource_id) REFERENCES public.catalog_resources(tenant_key, resource_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.load_test_runs
    ADD CONSTRAINT load_test_runs_tenant_key_environment_key_fkey FOREIGN KEY (tenant_key, environment_key) REFERENCES public.environments(tenant_key, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.load_test_runs
    ADD CONSTRAINT load_test_runs_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.namespace_grant_sources
    ADD CONSTRAINT namespace_grant_sources_pkey PRIMARY KEY (code);

ALTER TABLE ONLY public.quality_finding_comments
    ADD CONSTRAINT quality_finding_comments_author_id_fkey FOREIGN KEY (author_id) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.quality_finding_comments
    ADD CONSTRAINT quality_finding_comments_pkey PRIMARY KEY (tenant_key, finding_id, id);

ALTER TABLE ONLY public.quality_finding_comments
    ADD CONSTRAINT quality_finding_comments_tenant_key_finding_id_fkey FOREIGN KEY (tenant_key, finding_id) REFERENCES public.quality_findings(tenant_key, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.quality_finding_ignores
    ADD CONSTRAINT quality_finding_ignores_ignored_by_fkey FOREIGN KEY (ignored_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.quality_finding_ignores
    ADD CONSTRAINT quality_finding_ignores_pkey PRIMARY KEY (tenant_key, ignore_scope_urn, source_id, rule_id, finding_fingerprint);

ALTER TABLE ONLY public.quality_finding_ignores
    ADD CONSTRAINT quality_finding_ignores_revoked_by_fkey FOREIGN KEY (revoked_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.quality_finding_ignores
    ADD CONSTRAINT quality_finding_ignores_template_fkey FOREIGN KEY (tenant_key, template_resource_id) REFERENCES public.catalog_resources(tenant_key, resource_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.quality_finding_ignores
    ADD CONSTRAINT quality_finding_ignores_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.quality_findings
    ADD CONSTRAINT quality_findings_pkey PRIMARY KEY (tenant_key, id);

ALTER TABLE ONLY public.quality_findings
    ADD CONSTRAINT quality_findings_template_fkey FOREIGN KEY (tenant_key, template_resource_id) REFERENCES public.catalog_resources(tenant_key, resource_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.quality_findings
    ADD CONSTRAINT quality_findings_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.release_resource_types
    ADD CONSTRAINT release_resource_types_pkey PRIMARY KEY (resource_type);

ALTER TABLE ONLY public.release_resource_types
    ADD CONSTRAINT release_resource_types_wire_type_fkey FOREIGN KEY (wire_type) REFERENCES public.catalog_resource_types(resource_type);

ALTER TABLE ONLY public.release_resources
    ADD CONSTRAINT fk_release_resources_content FOREIGN KEY (tenant_key, content_hash) REFERENCES public.resource_contents(tenant_key, content_hash) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY public.release_resources
    ADD CONSTRAINT fk_release_resources_draft FOREIGN KEY (tenant_key, version_id, catalog_id) REFERENCES public.catalog_versions(tenant_key, version_id, catalog_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.release_resources
    ADD CONSTRAINT fk_release_resources_type FOREIGN KEY (resource_type) REFERENCES public.release_resource_types(resource_type);

ALTER TABLE ONLY public.release_resources
    ADD CONSTRAINT release_resources_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.release_resources
    ADD CONSTRAINT release_resources_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.resource_binaries
    ADD CONSTRAINT fk_resource_binaries_binary FOREIGN KEY (binary_hash) REFERENCES public.asset_content(content_hash);

ALTER TABLE ONLY public.resource_binaries
    ADD CONSTRAINT fk_resource_binaries_content FOREIGN KEY (tenant_key, content_hash) REFERENCES public.resource_contents(tenant_key, content_hash) ON DELETE CASCADE;

ALTER TABLE ONLY public.resource_binaries
    ADD CONSTRAINT pk_resource_binaries PRIMARY KEY (tenant_key, content_hash, binary_hash);

ALTER TABLE ONLY public.resource_content_kinds
    ADD CONSTRAINT resource_content_kinds_pkey PRIMARY KEY (kind);

ALTER TABLE ONLY public.resource_contents
    ADD CONSTRAINT fk_resource_contents_kind FOREIGN KEY (kind) REFERENCES public.resource_content_kinds(kind);

ALTER TABLE ONLY public.resource_contents
    ADD CONSTRAINT fk_resource_contents_tenant FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.resource_contents
    ADD CONSTRAINT pk_resource_contents PRIMARY KEY (tenant_key, content_hash);

ALTER TABLE ONLY public.resource_credentials
    ADD CONSTRAINT resource_credentials_pkey PRIMARY KEY (tenant_key, credential_id);

ALTER TABLE ONLY public.resource_credentials
    ADD CONSTRAINT resource_credentials_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.resource_ready_marks
    ADD CONSTRAINT fk_resource_ready_marks_version FOREIGN KEY (tenant_key, version_id) REFERENCES public.catalog_versions(tenant_key, version_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.resource_ready_marks
    ADD CONSTRAINT resource_ready_marks_pkey PRIMARY KEY (tenant_key, version_id, resource_id, part);

ALTER TABLE ONLY public.resource_ready_marks
    ADD CONSTRAINT resource_ready_marks_tenant_key_resource_id_fkey FOREIGN KEY (tenant_key, resource_id) REFERENCES public.catalog_resources(tenant_key, resource_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.resource_references
    ADD CONSTRAINT fk_resource_references_content FOREIGN KEY (tenant_key, content_hash) REFERENCES public.resource_contents(tenant_key, content_hash) ON DELETE CASCADE;

ALTER TABLE ONLY public.resource_references
    ADD CONSTRAINT fk_resource_references_type FOREIGN KEY (resource_type) REFERENCES public.catalog_resource_types(resource_type);

ALTER TABLE ONLY public.tenant_backups
    ADD CONSTRAINT tenant_backups_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.tenant_backups
    ADD CONSTRAINT tenant_backups_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.tenant_memberships
    ADD CONSTRAINT tenant_memberships_pkey PRIMARY KEY (user_id, tenant_key);

ALTER TABLE ONLY public.tenant_memberships
    ADD CONSTRAINT tenant_memberships_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.tenant_memberships
    ADD CONSTRAINT tenant_memberships_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.tenant_namespace_grants
    ADD CONSTRAINT tenant_namespace_grants_granted_through_fkey FOREIGN KEY (granted_through) REFERENCES public.namespace_grant_sources(code);

ALTER TABLE ONLY public.tenant_namespace_grants
    ADD CONSTRAINT tenant_namespace_grants_pkey PRIMARY KEY (tenant_key, authority, namespace);

ALTER TABLE ONLY public.tenant_namespace_grants
    ADD CONSTRAINT tenant_namespace_grants_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT fk_tenants_default_theme FOREIGN KEY (id, default_theme_resource_id) REFERENCES public.catalog_resources(tenant_key, resource_id);

ALTER TABLE ONLY public.tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_external_id_provider_unique UNIQUE (external_id, provider);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_provider_fkey FOREIGN KEY (provider) REFERENCES public.auth_providers(id);

ALTER TABLE ONLY public.web_session
    ADD CONSTRAINT web_session_pk PRIMARY KEY (primary_id);

ALTER TABLE ONLY public.web_session_attributes
    ADD CONSTRAINT web_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES public.web_session(primary_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.web_session_attributes
    ADD CONSTRAINT web_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name);

ALTER TABLE public.document_generation_requests
    ADD CONSTRAINT document_generation_requests_tenant_key_environment_key_fkey FOREIGN KEY (tenant_key, environment_key) REFERENCES public.environments(tenant_key, id) ON DELETE CASCADE;

ALTER TABLE public.document_generation_requests
    ADD CONSTRAINT document_generation_requests_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

ALTER TABLE public.documents
    ADD CONSTRAINT documents_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE public.documents
    ADD CONSTRAINT documents_tenant_key_fkey FOREIGN KEY (tenant_key) REFERENCES public.tenants(id) ON DELETE CASCADE;

CREATE CONSTRAINT TRIGGER trg_catalog_versions_next_check AFTER INSERT OR UPDATE OF state, name ON public.catalog_versions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.catalog_next_version_check();

CREATE CONSTRAINT TRIGGER trg_catalogs_next_check AFTER INSERT OR UPDATE OF next_version_id ON public.catalogs DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.catalog_next_version_check();

CREATE CONSTRAINT TRIGGER trg_release_resources_template_moved AFTER UPDATE OF catalog_id, version_id ON public.release_resources DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (((old.resource_type)::text = 'template'::text)) EXECUTE FUNCTION public.release_resource_variants_moved();

CREATE CONSTRAINT TRIGGER trg_release_resources_variant_parent AFTER INSERT OR UPDATE ON public.release_resources DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN ((new.parent_id IS NOT NULL)) EXECUTE FUNCTION public.release_resource_variant_parent();

CREATE DOMAIN public.asset_key AS character varying(50)
	CONSTRAINT asset_key_check CHECK (((VALUE)::text ~ '^[a-z0-9]+(-[a-z0-9]+)*$'::text));

CREATE DOMAIN public.attribute_key AS character varying(50)
	CONSTRAINT attribute_key_check CHECK (((VALUE)::text ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'::text));

CREATE DOMAIN public.catalog_key AS character varying(50)
	CONSTRAINT catalog_key_check CHECK (((VALUE)::text ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'::text));

CREATE DOMAIN public.code_list_auth_type AS text
	CONSTRAINT code_list_auth_type_check CHECK ((VALUE = ANY (ARRAY['NONE'::text, 'API_KEY'::text, 'BEARER'::text])));

CREATE DOMAIN public.code_list_key AS character varying(64)
	CONSTRAINT code_list_key_check CHECK (((VALUE)::text ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'::text));

CREATE DOMAIN public.code_list_source AS text
	CONSTRAINT code_list_source_check CHECK ((VALUE = ANY (ARRAY['CLASSPATH'::text, 'URL'::text, 'INLINE'::text])));

CREATE DOMAIN public.environment_key AS character varying(30)
	CONSTRAINT environment_key_check CHECK (((VALUE)::text ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'::text));

CREATE DOMAIN public.feature_key AS character varying(50)
	CONSTRAINT feature_key_check CHECK (((VALUE)::text ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'::text));

CREATE DOMAIN public.font_key AS character varying(64)
	CONSTRAINT font_key_check CHECK (((VALUE)::text ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'::text));

CREATE DOMAIN public.font_variant_source AS text
	CONSTRAINT font_variant_source_check CHECK ((VALUE = ANY (ARRAY['ASSET'::text, 'CLASSPATH'::text])));

CREATE DOMAIN public.generation_partition AS integer
	CONSTRAINT generation_partition_check CHECK (((VALUE >= 0) AND (VALUE < 64)));

CREATE DOMAIN public.generation_result_status AS text
	CONSTRAINT generation_result_status_check CHECK ((VALUE = ANY (ARRAY['COMPLETED'::text, 'FAILED'::text])));

CREATE DOMAIN public.stencil_key AS character varying(50)
	CONSTRAINT stencil_key_check CHECK (((VALUE)::text ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'::text));

CREATE DOMAIN public.template_key AS character varying(50)
	CONSTRAINT template_key_check CHECK (((VALUE)::text ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'::text));

CREATE DOMAIN public.tenant_key AS character varying(63)
	CONSTRAINT tenant_key_check CHECK (((VALUE)::text ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'::text));

CREATE DOMAIN public.theme_key AS character varying(50)
	CONSTRAINT theme_key_check CHECK (((VALUE)::text ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'::text));

CREATE DOMAIN public.variant_key AS character varying(50)
	CONSTRAINT variant_key_check CHECK (((VALUE)::text ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'::text));

CREATE FUNCTION public.add_release_resource_type() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO release_resource_types (resource_type, wire_type) VALUES (NEW.resource_type, NEW.resource_type)
    ON CONFLICT (resource_type) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.bump_catalog_version_revision() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.revision = OLD.revision + 1 THEN
        RETURN NEW;
    END IF;
    IF (NEW.name, NEW.default_theme_resource_id, NEW.presets) IS DISTINCT FROM (OLD.name, OLD.default_theme_resource_id, OLD.presets) THEN
        NEW.revision := OLD.revision + 1;
    ELSE
        NEW.revision := OLD.revision;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.bump_catalog_version_revision_for_dependency() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    changed RECORD;
BEGIN
    changed := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    UPDATE catalog_versions SET revision = revision + 1
    WHERE tenant_key = changed.tenant_key AND version_id = changed.version_id;
    IF TG_OP = 'UPDATE' AND OLD.version_id IS DISTINCT FROM NEW.version_id THEN
        UPDATE catalog_versions SET revision = revision + 1
        WHERE tenant_key = OLD.tenant_key AND version_id = OLD.version_id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION public.bump_content_revision() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    ignored TEXT[] := ARRAY['revision', 'updated_at', 'updated_by'] || TG_ARGV;
BEGIN
    IF NEW.revision = OLD.revision + 1 THEN
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - ignored) IS DISTINCT FROM (to_jsonb(OLD) - ignored) THEN
        NEW.revision := OLD.revision + 1;
    ELSE
        NEW.revision := OLD.revision;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.catalog_installed_release_id(p_tenant public.tenant_key, p_version uuid) RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
    WITH RECURSIVE chain AS (
        SELECT v.version_id, v.base_version_id, v.state, v.origin, 0 AS depth
        FROM catalog_versions v
        WHERE v.tenant_key = p_tenant AND v.version_id = p_version
        UNION ALL
        SELECT b.version_id, b.base_version_id, b.state, b.origin, chain.depth + 1
        FROM chain
        JOIN catalog_versions b ON b.tenant_key = p_tenant AND b.version_id = chain.base_version_id
        WHERE NOT (chain.state = 'released' AND chain.origin = 'installed')
    )
    SELECT version_id FROM chain WHERE state = 'released' AND origin = 'installed' ORDER BY depth LIMIT 1;
$$;

CREATE FUNCTION public.catalog_next_version_check() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- Checked against the state at commit, not the row as it was at the event: one transaction may
    -- move the pointer more than once (a release, a hand-over).
    IF TG_TABLE_NAME = 'catalogs' THEN
        IF EXISTS (
            SELECT 1 FROM catalogs c
            WHERE c.tenant_key = NEW.tenant_key AND c.catalog_id = NEW.catalog_id AND c.next_version_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM catalog_versions v
                              WHERE v.tenant_key = c.tenant_key AND v.version_id = c.next_version_id
                                AND v.catalog_id = c.catalog_id AND v.state = 'draft')
        ) THEN
            RAISE EXCEPTION 'The next version of catalog % is not one of its drafts', NEW.catalog_id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF EXISTS (
        SELECT 1 FROM catalog_versions v
        WHERE v.tenant_key = NEW.tenant_key AND v.catalog_id = NEW.catalog_id
          AND v.state = 'draft' AND v.name IS NULL
          AND v.version_id IS DISTINCT FROM (SELECT c.next_version_id FROM catalogs c
                                             WHERE c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id)
    ) THEN
        RAISE EXCEPTION 'A draft of catalog % without a name is not its next version', NEW.catalog_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION public.catalog_open_next_version() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    created UUID;
BEGIN
    IF NEW.next_version_id IS NULL THEN
        INSERT INTO catalog_versions (tenant_key, catalog_id, state)
        VALUES (NEW.tenant_key, NEW.catalog_id, 'draft')
        RETURNING version_id INTO created;
        UPDATE catalogs SET next_version_id = created
        WHERE tenant_key = NEW.tenant_key AND catalog_id = NEW.catalog_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.catalog_release_default_theme(p_tenant text, p_catalog uuid, p_version text, p_manifest jsonb) RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
SELECT r.resource_id FROM catalog_resources r
WHERE p_manifest #>> '{catalog,defaultTheme,key}' IS NOT NULL
  AND r.tenant_key = p_tenant AND r.resource_type = 'theme'
  AND r.catalog_id = resolve_catalog_alias(p_tenant, p_catalog, NULL, p_version, p_manifest #>> '{catalog,defaultTheme,catalogKey}')
  AND r.resource_key = p_manifest #>> '{catalog,defaultTheme,key}'
$$;

CREATE FUNCTION public.catalog_release_presets(p_manifest jsonb) RETURNS jsonb
    LANGUAGE sql IMMUTABLE
    AS $$
SELECT COALESCE(
    (SELECT jsonb_agg(jsonb_build_object('key', p ->> 'key', 'name', p ->> 'name', 'description', p -> 'description') ORDER BY p ->> 'key')
     FROM jsonb_array_elements(CASE WHEN jsonb_typeof(p_manifest #> '{catalog,presets}') = 'array' THEN p_manifest #> '{catalog,presets}' ELSE '[]'::jsonb END) p),
    '[]'::jsonb)
$$;

CREATE FUNCTION public.catalog_version_references(p_type text) RETURNS TABLE(tenant_key public.tenant_key, catalog_id uuid, version_id uuid, version character varying, resource_key text, resource_id uuid, ref_alias character varying, ref_type character varying, ref_key text, ref_catalog_id uuid)
    LANGUAGE sql STABLE
    AS $$
SELECT r.tenant_key, r.catalog_id, r.version_id, r.version, r.resource_key, r.resource_id,
       x.alias, x.resource_type, x.resource_key,
       resolve_catalog_alias(r.tenant_key, r.catalog_id, r.version_id, r.version, x.alias)
FROM catalog_version_resources(p_type) r
CROSS JOIN LATERAL (
    SELECT * FROM resource_references x WHERE x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash OFFSET 0
) x
$$;

CREATE FUNCTION public.catalog_version_resources(p_type text) RETURNS TABLE(tenant_key public.tenant_key, catalog_id uuid, version_id uuid, version character varying, resource_type character varying, resource_key text, resource_id uuid, content_hash character, fingerprint character varying, name character varying, description text, revision bigint, created_at timestamp with time zone, updated_at timestamp with time zone, created_by uuid, updated_by uuid)
    LANGUAGE sql STABLE
    AS $$
SELECT cv.tenant_key, cv.catalog_id, cv.version_id, vr.version, r.resource_type, r.resource_key, r.resource_id,
       r.content_hash, r.fingerprint, r.name, r.description, r.revision, r.created_at, r.updated_at, r.created_by, r.updated_by
FROM catalog_current_versions cv
JOIN catalog_versions vr ON vr.tenant_key = cv.tenant_key AND vr.version_id = cv.version_id
CROSS JOIN LATERAL (
    SELECT * FROM release_resources d
    WHERE d.tenant_key = cv.tenant_key AND d.catalog_id = cv.catalog_id AND d.version_id = cv.version_id AND d.resource_type = p_type
      AND d.parent_id IS NULL
    OFFSET 0
) r
$$;

CREATE FUNCTION public.content_version_move_children() RETURNS trigger
    LANGUAGE plpgsql
    AS $_$
DECLARE
    child_table TEXT := TG_ARGV[0];
    parent_column TEXT := TG_ARGV[1];
BEGIN
    IF NEW.version_id IS DISTINCT FROM OLD.version_id THEN
        EXECUTE format('UPDATE %I SET version_id = $1 WHERE tenant_key = $2 AND %I = $3 AND version_id = $4', child_table, parent_column)
            USING NEW.version_id, NEW.tenant_key, NEW.resource_id, OLD.version_id;
    END IF;
    RETURN NULL;
END;
$_$;

CREATE FUNCTION public.copy_catalog_version_content(p_tenant text, p_from uuid, p_to uuid) RETURNS void
    LANGUAGE plpgsql
    AS $_$
DECLARE
    t TEXT;
    columns TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['release_resources', 'resource_ready_marks', 'code_list_refreshes', 'catalog_version_dependencies']
    LOOP
        SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position) INTO columns
        FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = t AND is_generated = 'NEVER' AND column_name <> 'version_id';
        EXECUTE format('INSERT INTO %I (%s, version_id) SELECT %s, $2 FROM %I WHERE tenant_key = $1 AND version_id = $3', t, columns, columns, t)
            USING p_tenant, p_to, p_from;
    END LOOP;
END;
$_$;

CREATE FUNCTION public.epistola_catalog_id(p_tenant text, p_namespace text, p_key text) RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
    SELECT c.catalog_id FROM catalogs c WHERE c.tenant_key = p_tenant AND c.namespace = p_namespace AND c.catalog_key = p_key
$$;

CREATE FUNCTION public.epistola_create_partition(parent regclass, month date) RETURNS text
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
    AS $$
DECLARE
    parent_schema text;
    parent_name   text;
    child_name    text;
    start_date    date := date_trunc('month', month)::date;
    end_date      date := (date_trunc('month', month) + interval '1 month')::date;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_partitioned_table WHERE partrelid = parent AND partstrat = 'r'
    ) THEN
        RAISE EXCEPTION 'epistola_create_partition: % is not a RANGE-partitioned table', parent;
    END IF;

    SELECT n.nspname, c.relname INTO parent_schema, parent_name
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.oid = parent;

    child_name := parent_name || '_' || to_char(month, 'YYYY_MM');

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I.%I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
        parent_schema, child_name, parent::text, start_date, end_date
    );

    RETURN child_name;
END;
$$;

CREATE FUNCTION public.epistola_drop_partitions_before(parent regclass, cutoff date) RETURNS SETOF text
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'public'
    AS $$
DECLARE
    parent_name   text;
    cutoff_suffix text := to_char(date_trunc('month', cutoff), 'YYYY_MM');
    child         record;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_partitioned_table WHERE partrelid = parent AND partstrat = 'r'
    ) THEN
        RAISE EXCEPTION 'epistola_drop_partitions_before: % is not a RANGE-partitioned table', parent;
    END IF;

    SELECT c.relname INTO parent_name FROM pg_class c WHERE c.oid = parent;

    -- Drop only actual child partitions of `parent` (pg_inherits) whose monthly
    -- name sorts before the cutoff month — the same lexical rule the scheduler
    -- used, but the targets come from the catalog, never a caller-supplied name.
    FOR child IN
        SELECT n.nspname AS schema_name, c.relname AS table_name
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE i.inhparent = parent
          AND c.relname < parent_name || '_' || cutoff_suffix
        ORDER BY c.relname
    LOOP
        EXECUTE format('DROP TABLE IF EXISTS %I.%I CASCADE', child.schema_name, child.table_name);
        RETURN NEXT child.table_name;
    END LOOP;
END;
$$;

CREATE FUNCTION public.epistola_one_deployed_draft() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM catalog_versions v
               WHERE v.tenant_key = NEW.tenant_key AND v.version_id = NEW.target_version_id AND v.state = 'draft')
       AND EXISTS (SELECT 1 FROM environment_catalog_deployments d
                   JOIN catalog_versions o ON o.tenant_key = d.tenant_key AND o.version_id = d.target_version_id
                   WHERE d.tenant_key = NEW.tenant_key AND d.environment_key = NEW.environment_key
                     AND d.catalog_id = NEW.catalog_id AND d.id <> NEW.id AND o.state = 'draft') THEN
        RAISE EXCEPTION 'environment % already serves a draft of this catalog', NEW.environment_key
            USING ERRCODE = 'unique_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.epistola_uuidv7() RETURNS uuid
    LANGUAGE sql PARALLEL SAFE
    SET search_path TO 'pg_catalog'
    AS $$
    SELECT encode(
               substring(int8send(us / 1000) FROM 3)                                  -- 48-bit Unix ms
            || substring(int4send((28672 | ((us % 1000) * 4096 / 1000))::int) FROM 3) -- 0x7 + 12-bit sub-ms
            || substring(uuid_send(gen_random_uuid()) FROM 9),                          -- variant + random
           'hex')::uuid
    FROM (SELECT floor(extract(epoch FROM clock_timestamp()) * 1000000)::bigint AS us) now_us
$$;

CREATE FUNCTION public.epistola_version_key() RETURNS character varying
    LANGUAGE sql
    AS $$
SELECT string_agg(substr('abcdefghjkmnpqrstuvwxyz23456789', 1 + floor(random() * 31)::int, 1), '')
FROM generate_series(1, 8);
$$;

CREATE FUNCTION public.fill_generation_template_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.template_resource_id IS NULL THEN
        SELECT r.resource_id INTO NEW.template_resource_id
        FROM catalog_resources r
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
        WHERE r.tenant_key = NEW.tenant_key
          AND r.resource_type = 'template'
          AND c.registry = NEW.registry
          AND c.namespace = NEW.namespace
          AND c.catalog_key = NEW.catalog_key
          AND r.resource_key = NEW.template_key;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'no template % in catalog %/%/% for tenant %',
                NEW.template_key, NEW.registry, NEW.namespace, NEW.catalog_key, NEW.tenant_key;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.load_test_runs_follow_variant() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    DELETE FROM load_test_runs x
    WHERE x.tenant_key = OLD.tenant_key AND x.template_resource_id = OLD.resource_id
      AND x.variant_key IN (
          SELECT e ->> 'key' FROM resource_contents c, jsonb_array_elements(c.payload -> 'resource' -> 'variants') e
          WHERE c.tenant_key = OLD.tenant_key AND c.content_hash = OLD.content_hash
      )
      AND NOT EXISTS (
          SELECT 1 FROM release_resources r
          JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
          CROSS JOIN LATERAL jsonb_array_elements(c.payload -> 'resource' -> 'variants') e
          WHERE r.tenant_key = OLD.tenant_key AND r.resource_id = OLD.resource_id AND r.resource_type = 'template'
            AND e ->> 'key' = x.variant_key
      );
    RETURN NULL;
END;
$$;

CREATE FUNCTION public.quality_findings_follow_variant() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    DELETE FROM quality_findings x
    WHERE x.tenant_key = OLD.tenant_key AND x.template_resource_id = OLD.resource_id
      AND x.variant_key IN (
          SELECT e ->> 'key' FROM resource_contents c, jsonb_array_elements(c.payload -> 'resource' -> 'variants') e
          WHERE c.tenant_key = OLD.tenant_key AND c.content_hash = OLD.content_hash
      )
      AND NOT EXISTS (
          SELECT 1 FROM release_resources r
          JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
          CROSS JOIN LATERAL jsonb_array_elements(c.payload -> 'resource' -> 'variants') e
          WHERE r.tenant_key = OLD.tenant_key AND r.resource_id = OLD.resource_id AND r.resource_type = 'template'
            AND e ->> 'key' = x.variant_key
      );
    RETURN NULL;
END;
$$;

CREATE FUNCTION public.release_resource_is_draft(p_tenant text, p_version_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
SELECT EXISTS (SELECT 1 FROM catalog_versions v WHERE v.tenant_key = p_tenant AND v.version_id = p_version_id AND v.state = 'draft')
$$;

CREATE FUNCTION public.release_resource_is_released(p_tenant text, p_version_id uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
SELECT EXISTS (SELECT 1 FROM catalog_versions v WHERE v.tenant_key = p_tenant AND v.version_id = p_version_id AND v.state = 'released')
$$;

CREATE FUNCTION public.release_resource_variant_parent() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM release_resources v
        WHERE v.tenant_key = NEW.tenant_key AND v.catalog_id = NEW.catalog_id AND v.parent_id = NEW.parent_id
          AND v.resource_type = 'variant' AND v.resource_key = NEW.resource_key AND v.version_id = NEW.version_id
    ) AND NOT EXISTS (
        SELECT 1 FROM release_resources r
        WHERE r.tenant_key = NEW.tenant_key AND r.catalog_id = NEW.catalog_id AND r.resource_id = NEW.parent_id
          AND r.resource_type = 'template' AND r.version_id = NEW.version_id
    ) THEN
        RAISE EXCEPTION 'variant % of template % has no template row in its version', NEW.resource_key, NEW.parent_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION public.release_resource_variants_deleted() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    DELETE FROM release_resources v
    WHERE v.tenant_key = OLD.tenant_key AND v.catalog_id = OLD.catalog_id AND v.parent_id = OLD.resource_id
      AND v.version_id = OLD.version_id;
    RETURN NULL;
END;
$$;

CREATE FUNCTION public.release_resource_variants_moved() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM release_resources v
        WHERE v.tenant_key = OLD.tenant_key AND v.catalog_id = OLD.catalog_id AND v.parent_id = OLD.resource_id
          AND v.version_id = OLD.version_id
    ) AND NOT EXISTS (
        SELECT 1 FROM release_resources r
        WHERE r.tenant_key = OLD.tenant_key AND r.catalog_id = OLD.catalog_id AND r.resource_id = OLD.resource_id
          AND r.resource_type = 'template' AND r.version_id = OLD.version_id
    ) THEN
        RAISE EXCEPTION 'template % moved without its variants', OLD.resource_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION public.resolve_catalog_alias(p_tenant text, p_catalog uuid, p_version_id uuid, p_version text, p_alias text) RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
SELECT CASE
    WHEN p_alias IS NULL OR p_alias = (SELECT c.catalog_key FROM catalogs c WHERE c.tenant_key = p_tenant AND c.catalog_id = p_catalog)
        THEN p_catalog
    ELSE (SELECT epistola_catalog_id(p_tenant, d.namespace, d.dependency_key) FROM catalog_version_dependencies d
          WHERE d.tenant_key = p_tenant AND d.alias = p_alias
            AND d.version_id = COALESCE(p_version_id, (SELECT v.version_id FROM catalog_versions v
                                                       WHERE v.tenant_key = p_tenant AND v.catalog_id = p_catalog
                                                         AND v.version = p_version AND v.state = 'released')))
END
$$;

CREATE FUNCTION public.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.sync_catalog_resource_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $_$
DECLARE
    resource_type_value TEXT := TG_ARGV[0];
    key_column TEXT := TG_ARGV[1];
    resource_key_value TEXT;
    existing_resource_id UUID;
    held_elsewhere BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE tenant_key = $1 AND resource_id = $2)', TG_TABLE_NAME)
            INTO held_elsewhere USING OLD.tenant_key, OLD.resource_id;
        -- A release holds it too: releasing a draft that a higher draft does not follow discards the
        -- draft, and what only it held lives on in that release. Keeping the identity keeps the
        -- resource's history and findings with it, and a resource created again at its address adopts it.
        IF NOT held_elsewhere THEN
            SELECT EXISTS (SELECT 1 FROM release_resources WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id)
                INTO held_elsewhere;
        END IF;
        IF NOT held_elsewhere THEN
            DELETE FROM catalog_resources
            WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id;
        END IF;
        RETURN OLD;
    END IF;

    resource_key_value := to_jsonb(NEW) ->> key_column;

    IF TG_OP = 'INSERT' THEN
        SELECT resource_id INTO existing_resource_id
        FROM catalog_resources
        WHERE tenant_key = NEW.tenant_key
          AND resource_type = resource_type_value
          AND catalog_id = NEW.catalog_id
          AND resource_key = resource_key_value;

        IF NEW.resource_id IS NULL THEN
            -- Nothing supplied: adopt the identity already registered at this address, which another
            -- version of the catalog may hold (imports use INSERT .. ON CONFLICT DO UPDATE, which runs
            -- this BEFORE INSERT trigger too).
            NEW.resource_id := COALESCE(existing_resource_id, epistola_uuidv7());
        ELSIF existing_resource_id IS NOT NULL AND existing_resource_id <> NEW.resource_id THEN
            -- A caller that names an identity means it; never silently prefer the incumbent.
            RAISE EXCEPTION 'catalog resource % is already registered at %/% under identity %',
                NEW.resource_id, NEW.catalog_id, resource_key_value, existing_resource_id;
        END IF;

        -- An insert registers an identity, or joins the one registered at its address; it never moves
        -- one. Under concurrency the address it adopted may have moved since its snapshot (a
        -- relocation committing meanwhile): the conflict check sees the committed row, and refuses.
        INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, catalog_id, resource_key)
        VALUES (NEW.tenant_key, NEW.resource_id, resource_type_value, NEW.catalog_id, resource_key_value)
        ON CONFLICT (tenant_key, resource_id) DO UPDATE
        SET catalog_id = EXCLUDED.catalog_id,
            resource_key = EXCLUDED.resource_key
        WHERE catalog_resources.resource_type = EXCLUDED.resource_type
          AND catalog_resources.catalog_id = EXCLUDED.catalog_id
          AND catalog_resources.resource_key = EXCLUDED.resource_key;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'catalog resource % is registered with another type or at another address than %/%',
                NEW.resource_id, NEW.catalog_id, resource_key_value;
        END IF;
    ELSE
        IF NEW.resource_id <> OLD.resource_id THEN
            RAISE EXCEPTION 'catalog resource_id is immutable';
        END IF;
        IF NEW.catalog_id IS DISTINCT FROM OLD.catalog_id OR resource_key_value IS DISTINCT FROM (to_jsonb(OLD) ->> key_column) THEN
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE tenant_key = $1 AND resource_id = $2 AND version_id <> $3)', TG_TABLE_NAME)
                INTO held_elsewhere USING OLD.tenant_key, OLD.resource_id, OLD.version_id;
            IF held_elsewhere THEN
                RAISE EXCEPTION 'catalog resource % is held by another version of its catalog; it cannot move or be renamed', OLD.resource_id;
            END IF;
        END IF;
        UPDATE catalog_resources
        SET catalog_id = NEW.catalog_id,
            resource_key = resource_key_value
        WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id;
        -- A missing registry row would let the domain row move while the registry keeps the old
        -- address, silently; every public address lookup reads the registry.
        IF NOT FOUND THEN
            RAISE EXCEPTION 'catalog resource % has no identity row', OLD.resource_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$_$;

CREATE FUNCTION public.sync_draft_resource_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$

DECLARE
    existing_resource_id UUID;
    held_elsewhere BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        SELECT EXISTS (SELECT 1 FROM release_resources WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id)
            INTO held_elsewhere;
        IF NOT held_elsewhere THEN
            DELETE FROM catalog_resources WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id;
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT resource_id INTO existing_resource_id
        FROM catalog_resources
        WHERE tenant_key = NEW.tenant_key AND resource_type = NEW.resource_type
          AND catalog_id = NEW.catalog_id AND resource_key = NEW.resource_key;
        IF NEW.resource_id IS NULL THEN
            NEW.resource_id := COALESCE(existing_resource_id, epistola_uuidv7());
        ELSIF existing_resource_id IS NOT NULL AND existing_resource_id <> NEW.resource_id THEN
            RAISE EXCEPTION 'catalog resource % is already registered at %/% under identity %',
                NEW.resource_id, NEW.catalog_id, NEW.resource_key, existing_resource_id;
        END IF;
        INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, catalog_id, resource_key)
        VALUES (NEW.tenant_key, NEW.resource_id, NEW.resource_type, NEW.catalog_id, NEW.resource_key)
        ON CONFLICT (tenant_key, resource_id) DO UPDATE
        SET catalog_id = EXCLUDED.catalog_id, resource_key = EXCLUDED.resource_key
        WHERE catalog_resources.resource_type = EXCLUDED.resource_type
          AND catalog_resources.catalog_id = EXCLUDED.catalog_id
          AND catalog_resources.resource_key = EXCLUDED.resource_key;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'catalog resource % is registered with another type or at another address than %/%',
                NEW.resource_id, NEW.catalog_id, NEW.resource_key;
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.resource_id <> OLD.resource_id THEN
        RAISE EXCEPTION 'catalog resource_id is immutable';
    END IF;
    IF NEW.catalog_id IS DISTINCT FROM OLD.catalog_id OR NEW.resource_key IS DISTINCT FROM OLD.resource_key THEN
        SELECT EXISTS (SELECT 1 FROM release_resources WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id
                       AND version_id <> OLD.version_id AND release_resource_is_draft(tenant_key, version_id))
            INTO held_elsewhere;
        IF held_elsewhere THEN
            RAISE EXCEPTION 'catalog resource % is held by another version of its catalog; it cannot move or be renamed', OLD.resource_id;
        END IF;
        UPDATE catalog_resources SET catalog_id = NEW.catalog_id, resource_key = NEW.resource_key
        WHERE tenant_key = OLD.tenant_key AND resource_id = OLD.resource_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'catalog resource % has no identity row', OLD.resource_id;
        END IF;
    END IF;
    RETURN NEW;
END;

$$;

CREATE FUNCTION public.template_variant_digest(p_template_hash text, p_variant_hash text) RETURNS text
    LANGUAGE sql IMMUTABLE
    AS $$
SELECT encode(sha256(convert_to(p_template_hash || ':' || p_variant_hash, 'UTF8')), 'hex')
$$;

CREATE FUNCTION public.touch_catalog_content_on_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- Matches nothing when the catalog itself is being deleted, because the parent row is gone
    -- before its children cascade. That is correct: there is no working copy left to describe.
    UPDATE catalogs
    SET content_updated_at = now()
    WHERE tenant_key = OLD.tenant_key AND catalog_id = OLD.catalog_id;
    RETURN OLD;
END;
$$;

CREATE FUNCTION public.touch_catalog_content_on_variant_delete() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE catalogs c
    SET content_updated_at = now()
    FROM document_templates t
    WHERE t.tenant_key = OLD.tenant_key AND t.resource_id = OLD.template_resource_id
      AND c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id;
    RETURN OLD;
END;
$$;

CREATE FUNCTION public.variant_resource_id(p_parent uuid, p_key text) RETURNS uuid
    LANGUAGE sql IMMUTABLE
    AS $$
SELECT CAST(md5(CAST(p_parent AS TEXT) || '/' || p_key) AS UUID)
$$;

CREATE INDEX audit_log_202606_action_occurred_at_idx ON public.audit_log_202606 USING btree (action, occurred_at DESC);

CREATE INDEX audit_log_202606_actor_user_id_occurred_at_idx ON public.audit_log_202606 USING btree (actor_user_id, occurred_at DESC);

CREATE INDEX audit_log_202606_tenant_key_occurred_at_idx ON public.audit_log_202606 USING btree (tenant_key, occurred_at DESC);

CREATE INDEX catalog_release_publications_work ON public.catalog_release_publications USING btree (status, next_attempt_at) WHERE ((status)::text = ANY ((ARRAY['READY'::character varying, 'SUBMITTED'::character varying, 'RETRY'::character varying])::text[]));

CREATE INDEX catalog_upstream_checks_due ON public.catalog_upstream_checks USING btree (next_check_at);

CREATE INDEX document_generation_requests_202606_batch_id_idx ON public.document_generation_requests_202606 USING btree (batch_id) WHERE (batch_id IS NOT NULL);

CREATE INDEX document_generation_requests_202606_batch_id_status_idx ON public.document_generation_requests_202606 USING btree (batch_id, status) WHERE (batch_id IS NOT NULL);

CREATE INDEX document_generation_requests_202606_created_at_idx ON public.document_generation_requests_202606 USING btree (created_at DESC);

CREATE INDEX document_generation_requests_202606_document_key_idx ON public.document_generation_requests_202606 USING btree (document_key) WHERE (document_key IS NOT NULL);

CREATE INDEX document_generation_requests_202606_expires_at_idx ON public.document_generation_requests_202606 USING btree (expires_at) WHERE (expires_at IS NOT NULL);

CREATE INDEX document_generation_requests_202606_status_created_at_idx ON public.document_generation_requests_202606 USING btree (status, created_at) WHERE ((status)::text = 'PENDING'::text);

CREATE INDEX document_generation_requests_202606_status_idx ON public.document_generation_requests_202606 USING btree (status);

CREATE INDEX document_generation_requests_202606_template_resource_id_idx ON public.document_generation_requests_202606 USING btree (tenant_key, template_resource_id, created_at DESC) WHERE (template_resource_id IS NOT NULL);

CREATE INDEX document_generation_requests_202606_tenant_key_template_key_idx ON public.document_generation_requests_202606 USING btree (tenant_key, template_key);

CREATE INDEX document_generation_requests_2026_tenant_key_correlation_id_idx ON public.document_generation_requests_202606 USING btree (tenant_key, correlation_id) WHERE (correlation_id IS NOT NULL);

CREATE INDEX documents_202606_created_at_idx ON public.documents_202606 USING btree (created_at DESC);

CREATE INDEX documents_202606_template_resource_id_idx ON public.documents_202606 USING btree (tenant_key, template_resource_id, created_at DESC) WHERE (template_resource_id IS NOT NULL);

CREATE INDEX documents_202606_tenant_key_correlation_id_idx ON public.documents_202606 USING btree (tenant_key, correlation_id) WHERE (correlation_id IS NOT NULL);

CREATE INDEX documents_202606_tenant_key_template_key_idx ON public.documents_202606 USING btree (tenant_key, template_key);

CREATE INDEX event_log_<month>_event_type_idx ON public.event_log_<month> USING btree (event_type);

CREATE INDEX event_log_<month>_event_type_idx ON public.event_log_<month> USING btree (event_type);

CREATE INDEX event_log_<month>_tenant_key_occurred_at_idx ON public.event_log_<month> USING btree (tenant_key, occurred_at DESC) WHERE (tenant_key IS NOT NULL);

CREATE INDEX event_log_<month>_tenant_key_occurred_at_idx ON public.event_log_<month> USING btree (tenant_key, occurred_at DESC) WHERE (tenant_key IS NOT NULL);

CREATE INDEX generation_results_<month>_partition_sequence_idx ON public.generation_results_<month> USING btree (partition, sequence);

CREATE INDEX generation_results_<month>_partition_sequence_idx ON public.generation_results_<month> USING btree (partition, sequence);

CREATE INDEX generation_results_<month>_tenant_key_idx ON public.generation_results_<month> USING btree (tenant_key);

CREATE INDEX generation_results_<month>_tenant_key_idx ON public.generation_results_<month> USING btree (tenant_key);

CREATE INDEX idx_api_keys_tenant_key ON public.api_keys USING btree (tenant_key);

CREATE INDEX idx_application_log_level_occurred ON public.application_log USING btree (level, occurred_at DESC);

CREATE INDEX idx_application_log_occurred ON public.application_log USING btree (occurred_at DESC);

CREATE INDEX idx_application_log_tenant_occurred ON public.application_log USING btree (tenant_key, occurred_at DESC);

CREATE INDEX idx_audit_log_action_occurred ON ONLY public.audit_log USING btree (action, occurred_at DESC);

CREATE INDEX idx_audit_log_actor_occurred ON ONLY public.audit_log USING btree (actor_user_id, occurred_at DESC);

CREATE INDEX idx_audit_log_tenant_occurred ON ONLY public.audit_log USING btree (tenant_key, occurred_at DESC);

CREATE INDEX idx_catalog_draft_merges_merged_by ON public.catalog_draft_merges USING btree (merged_by);

CREATE INDEX idx_catalog_version_dependencies_target ON public.catalog_version_dependencies USING btree (tenant_key, target_version_id) WHERE (target_version_id IS NOT NULL);

CREATE INDEX idx_catalog_versions_release_content_hash ON public.catalog_versions USING btree (tenant_key, catalog_id, content_hash) WHERE ((state)::text = 'released'::text);

CREATE INDEX idx_catalog_versions_release_order ON public.catalog_versions USING btree (tenant_key, catalog_id, version_major DESC, version_minor DESC, version_patch DESC, ((version_label IS NULL)) DESC, version_label COLLATE "C" DESC, version_label_number DESC) WHERE ((state)::text = 'released'::text);

CREATE INDEX idx_catalog_versions_released_at ON public.catalog_versions USING btree (tenant_key, catalog_id, released_at DESC) WHERE ((state)::text = 'released'::text);

CREATE INDEX idx_cluster_nodes_last_seen_at ON public.cluster_nodes USING btree (last_seen_at);

CREATE INDEX idx_cluster_scheduled_task_registrations_task ON public.cluster_scheduled_task_registrations USING btree (task_key);

CREATE INDEX idx_cluster_tasks_scheduled_due ON public.cluster_tasks_scheduled USING btree (enabled, next_due_at) WHERE (enabled = true);

CREATE INDEX idx_cluster_tasks_scheduled_lease_owner ON public.cluster_tasks_scheduled USING btree (lease_owner_node_id);

CREATE INDEX idx_cluster_tasks_scheduled_node_due ON public.cluster_tasks_scheduled_node_state USING btree (task_key, node_id, next_due_at);

CREATE INDEX idx_cluster_tasks_scheduled_node_lease ON public.cluster_tasks_scheduled_node_state USING btree (task_key, node_id, lease_expires_at);

CREATE INDEX idx_cluster_tasks_scheduled_required_capability ON public.cluster_tasks_scheduled USING btree (required_capability);

CREATE INDEX idx_cluster_tasks_scheduled_routing_key ON public.cluster_tasks_scheduled USING btree (routing_key);

CREATE INDEX idx_cluster_tasks_scheduled_tenant_key ON public.cluster_tasks_scheduled USING btree (tenant_key) WHERE (tenant_key IS NOT NULL);

CREATE INDEX idx_cluster_timers_due ON public.cluster_timers USING btree (status, due_at);

CREATE INDEX idx_cluster_timers_lease_owner ON public.cluster_timers USING btree (lease_owner_node_id);

CREATE INDEX idx_cluster_timers_required_capability ON public.cluster_timers USING btree (required_capability);

CREATE INDEX idx_cluster_timers_routing_key ON public.cluster_timers USING btree (routing_key);

CREATE INDEX idx_cluster_timers_tenant_key ON public.cluster_timers USING btree (tenant_key) WHERE (tenant_key IS NOT NULL);

CREATE INDEX idx_consumer_node_assignments_tenant_consumer_lastseen ON public.consumer_node_assignments USING btree (tenant_key, consumer_id, last_seen_at);

CREATE INDEX idx_dgr_batch_status ON ONLY public.document_generation_requests USING btree (batch_id, status) WHERE (batch_id IS NOT NULL);

CREATE INDEX idx_dgr_pending_poll ON ONLY public.document_generation_requests USING btree (status, created_at) WHERE ((status)::text = 'PENDING'::text);

CREATE INDEX idx_documents_correlation_id ON ONLY public.documents USING btree (tenant_key, correlation_id) WHERE (correlation_id IS NOT NULL);

CREATE INDEX idx_documents_created_at ON ONLY public.documents USING btree (created_at DESC);

CREATE INDEX idx_documents_template_key ON ONLY public.documents USING btree (tenant_key, template_key);

CREATE INDEX idx_documents_template_resource_id ON ONLY public.documents USING btree (tenant_key, template_resource_id, created_at DESC) WHERE (template_resource_id IS NOT NULL);

CREATE INDEX idx_environment_catalog_deployments_version ON public.environment_catalog_deployments USING btree (tenant_key, target_version_id);

CREATE INDEX idx_environment_deployment_log_environment ON public.environment_deployment_log USING btree (tenant_key, environment_key, changed_at DESC);

CREATE INDEX idx_event_log_tenant_occurred ON ONLY public.event_log USING btree (tenant_key, occurred_at DESC) WHERE (tenant_key IS NOT NULL);

CREATE INDEX idx_event_log_type ON ONLY public.event_log USING btree (event_type);

CREATE INDEX idx_feedback_created_at ON public.feedback USING btree (tenant_key, created_at DESC);

CREATE INDEX idx_feedback_created_by ON public.feedback USING btree (tenant_key, created_by);

CREATE INDEX idx_feedback_external_ref ON public.feedback USING btree (tenant_key, external_ref) WHERE (external_ref IS NOT NULL);

CREATE INDEX idx_feedback_status ON public.feedback USING btree (tenant_key, status);

CREATE INDEX idx_feedback_sync_status ON public.feedback USING btree (sync_status) WHERE ((sync_status)::text = 'PENDING'::text);

CREATE INDEX idx_generation_batches_created_at ON public.document_generation_batches USING btree (created_at DESC);

CREATE INDEX idx_generation_batches_tenant_key ON public.document_generation_batches USING btree (tenant_key);

CREATE INDEX idx_generation_requests_batch_key ON ONLY public.document_generation_requests USING btree (batch_id) WHERE (batch_id IS NOT NULL);

CREATE INDEX idx_generation_requests_correlation_id ON ONLY public.document_generation_requests USING btree (tenant_key, correlation_id) WHERE (correlation_id IS NOT NULL);

CREATE INDEX idx_generation_requests_created_at ON ONLY public.document_generation_requests USING btree (created_at DESC);

CREATE INDEX idx_generation_requests_document_key ON ONLY public.document_generation_requests USING btree (document_key) WHERE (document_key IS NOT NULL);

CREATE INDEX idx_generation_requests_expires_at ON ONLY public.document_generation_requests USING btree (expires_at) WHERE (expires_at IS NOT NULL);

CREATE INDEX idx_generation_requests_status ON ONLY public.document_generation_requests USING btree (status);

CREATE INDEX idx_generation_requests_template_key ON ONLY public.document_generation_requests USING btree (tenant_key, template_key);

CREATE INDEX idx_generation_requests_template_resource_id ON ONLY public.document_generation_requests USING btree (tenant_key, template_resource_id, created_at DESC) WHERE (template_resource_id IS NOT NULL);

CREATE INDEX idx_generation_results_partition_sequence ON ONLY public.generation_results USING btree (partition, sequence);

CREATE INDEX idx_generation_results_tenant_key ON ONLY public.generation_results USING btree (tenant_key);

CREATE INDEX idx_load_test_runs_batch_id ON public.load_test_runs USING btree (batch_id) WHERE (batch_id IS NOT NULL);

CREATE INDEX idx_load_test_runs_created_at ON public.load_test_runs USING btree (created_at DESC);

CREATE INDEX idx_load_test_runs_status ON public.load_test_runs USING btree (status);

CREATE INDEX idx_load_test_runs_tenant_key ON public.load_test_runs USING btree (tenant_key);

CREATE INDEX idx_ltr_pending_poll ON public.load_test_runs USING btree (status, created_at) WHERE ((status)::text = 'PENDING'::text);

CREATE INDEX idx_quality_findings_last_seen ON public.quality_findings USING btree (tenant_key, last_seen_at DESC);

CREATE INDEX idx_quality_findings_template ON public.quality_findings USING btree (tenant_key, template_resource_id, variant_key);

CREATE INDEX idx_quality_ignores_disposition ON public.quality_finding_ignores USING btree (tenant_key, source_id, updated_at DESC);

CREATE INDEX idx_release_resources_content ON public.release_resources USING btree (tenant_key, content_hash);

CREATE INDEX idx_release_resources_parent ON public.release_resources USING btree (tenant_key, parent_id, resource_key) WHERE (parent_id IS NOT NULL);

CREATE INDEX idx_release_resources_resource ON public.release_resources USING btree (tenant_key, resource_id);

CREATE INDEX idx_resource_binaries_binary ON public.resource_binaries USING btree (binary_hash);

CREATE INDEX idx_resource_binaries_tenant_binary ON public.resource_binaries USING btree (tenant_key, binary_hash);

CREATE INDEX idx_resource_references_target ON public.resource_references USING btree (tenant_key, resource_type, resource_key);

CREATE INDEX idx_tenant_backups_tenant_captured ON public.tenant_backups USING btree (tenant_key, captured_at DESC);

CREATE INDEX idx_tenant_memberships_tenant_key ON public.tenant_memberships USING btree (tenant_key);

CREATE INDEX idx_tenant_memberships_user_id ON public.tenant_memberships USING btree (user_id);

CREATE INDEX idx_tenants_default_theme_resource_id ON public.tenants USING btree (default_theme_resource_id) WHERE (default_theme_resource_id IS NOT NULL);

CREATE INDEX idx_tenants_name ON public.tenants USING btree (name);

CREATE INDEX idx_users_email ON public.users USING btree (email);

CREATE INDEX web_session_ix2 ON public.web_session USING btree (expiry_time);

CREATE INDEX web_session_ix3 ON public.web_session USING btree (principal_name);

CREATE SEQUENCE public.generation_results_sequence_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public.api_keys (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    enabled boolean DEFAULT true NOT NULL,
    expires_at timestamp with time zone,
    id uuid NOT NULL,
    key_hash character varying(128) NOT NULL,
    key_prefix character varying(20) NOT NULL,
    last_used_at timestamp with time zone,
    name character varying(100) NOT NULL,
    revoked_at timestamp with time zone,
    revoked_by uuid,
    roles character varying(30)[] NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.app_metadata (
    key character varying(100) NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    value jsonb NOT NULL
);

CREATE TABLE public.application_log (
    attributes jsonb,
    exception text,
    id uuid NOT NULL,
    instance_id text NOT NULL,
    level text NOT NULL,
    logger text NOT NULL,
    message text NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    span_id text,
    tenant_key public.tenant_key,
    thread text,
    trace_id text
);

CREATE TABLE public.asset_content (
    content bytea NOT NULL,
    content_hash text NOT NULL,
    content_type character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    size_bytes bigint NOT NULL
);

CREATE TABLE public.asset_types (
    media_type character varying(50) NOT NULL
);

CREATE TABLE public.audit_log (
    CONSTRAINT audit_log_operation_check CHECK (((operation)::text = ANY ((ARRAY['WRITE'::character varying, 'READ'::character varying])::text[]))),
    CONSTRAINT audit_log_outcome_check CHECK (((outcome)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILURE'::character varying])::text[]))),
    action character varying(255) NOT NULL,
    actor_user_id uuid,
    details jsonb,
    entity_id character varying(255),
    entity_type character varying(255),
    error_code character varying(255),
    id uuid NOT NULL,
    instance_id text,
    occurred_at timestamp with time zone NOT NULL,
    operation character varying(8) NOT NULL,
    outcome character varying(16) NOT NULL,
    tenant_key public.tenant_key
)
PARTITION BY RANGE (occurred_at);

CREATE TABLE public.audit_log_202606 (
    CONSTRAINT audit_log_operation_check CHECK (((operation)::text = ANY ((ARRAY['WRITE'::character varying, 'READ'::character varying])::text[]))),
    CONSTRAINT audit_log_outcome_check CHECK (((outcome)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILURE'::character varying])::text[]))),
    action character varying(255) NOT NULL,
    actor_user_id uuid,
    details jsonb,
    entity_id character varying(255),
    entity_type character varying(255),
    error_code character varying(255),
    id uuid NOT NULL,
    instance_id text,
    occurred_at timestamp with time zone NOT NULL,
    operation character varying(8) NOT NULL,
    outcome character varying(16) NOT NULL,
    tenant_key public.tenant_key
);

CREATE TABLE public.auth_providers (
    description text NOT NULL,
    id character varying(50) NOT NULL
);

CREATE TABLE public.catalog_draft_merges (
    held_fingerprints jsonb,
    merge_base character varying(50),
    merged_at timestamp with time zone DEFAULT now() NOT NULL,
    merged_by uuid,
    release_version character varying(128) NOT NULL,
    resolutions jsonb DEFAULT '{}'::jsonb NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    version_id uuid NOT NULL
);

CREATE TABLE public.catalog_exchange_bindings (
    bound_at timestamp with time zone DEFAULT now() NOT NULL,
    catalog_key public.catalog_key NOT NULL,
    namespace text NOT NULL,
    published_at timestamp with time zone,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.catalog_release_merges (
    merged_fingerprint character varying(80),
    merged_version character varying(128) NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    version_id uuid NOT NULL
);

CREATE TABLE public.catalog_release_publication_archives (
    bytes bytea NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    publication_id uuid NOT NULL,
    size_bytes bigint NOT NULL
);

CREATE TABLE public.catalog_release_publications (
    CONSTRAINT catalog_release_publications_status_check CHECK (((status)::text = ANY ((ARRAY['READY'::character varying, 'SUBMITTED'::character varying, 'RETRY'::character varying, 'ACCEPTED'::character varying, 'REJECTED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    attempts integer DEFAULT 0 NOT NULL,
    catalog_id uuid NOT NULL,
    claimed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    error_code text,
    error_detail text,
    fingerprint character varying(80) NOT NULL,
    id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    namespace text NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    remote_publication_id uuid,
    status character varying(30) NOT NULL,
    submitted_at timestamp with time zone,
    tenant_key public.tenant_key NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version character varying(128) NOT NULL
);

CREATE TABLE public.catalog_resource_types (
    resource_type character varying(20) NOT NULL
);

CREATE TABLE public.catalog_resources (
    catalog_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    resource_id uuid NOT NULL,
    resource_key text NOT NULL,
    resource_type character varying(20) NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.catalog_upstream_checks (
    CONSTRAINT catalog_upstream_checks_installed_availability_check CHECK ((installed_availability = ANY (ARRAY['AVAILABLE'::text, 'BLOCKED'::text, 'WITHDRAWN'::text]))),
    available_published_at timestamp with time zone,
    available_release_version text,
    available_schema_version integer,
    catalog_id uuid NOT NULL,
    check_attempts integer DEFAULT 0 NOT NULL,
    claimed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    error_code text,
    error_detail text,
    installed_archive_sha256 character(64),
    installed_availability text,
    installed_availability_checked_at timestamp with time zone,
    last_checked_at timestamp with time zone,
    next_check_at timestamp with time zone DEFAULT now() NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.catalog_version_dependencies (
    "position" integer NOT NULL,
    CONSTRAINT chk_catalog_version_dependencies_pinned CHECK (((release_version IS NULL) = (release_fingerprint IS NULL))),
    alias character varying(50) NOT NULL,
    catalog_id uuid NOT NULL,
    dependency_key character varying(50) NOT NULL,
    namespace text NOT NULL,
    registry text NOT NULL,
    release_fingerprint character varying(80),
    release_version character varying(128),
    target_version_id uuid,
    tenant_key public.tenant_key NOT NULL,
    version_id uuid NOT NULL
);

CREATE TABLE public.catalog_versions (
    CONSTRAINT chk_catalog_versions_draft_unnumbered CHECK ((((state)::text = 'released'::text) OR (version IS NULL))),
    CONSTRAINT chk_catalog_versions_installed_is_released CHECK ((((origin)::text <> 'installed'::text) OR ((state)::text = 'released'::text))),
    CONSTRAINT chk_catalog_versions_origin CHECK (((origin)::text = ANY ((ARRAY['authored'::character varying, 'installed'::character varying])::text[]))),
    CONSTRAINT chk_catalog_versions_released CHECK ((((state)::text = 'released'::text) = ((version IS NOT NULL) AND (fingerprint IS NOT NULL) AND (content_hash IS NOT NULL) AND (released_at IS NOT NULL)))),
    CONSTRAINT chk_catalog_versions_state CHECK (((state)::text = ANY ((ARRAY['draft'::character varying, 'released'::character varying])::text[]))),
    base_fingerprint character varying(80),
    base_fingerprints jsonb,
    base_version character varying(128),
    base_version_id uuid,
    catalog_id uuid NOT NULL,
    content_hash character varying(80),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_from character varying(128),
    default_theme_resource_id uuid,
    fingerprint character varying(80),
    manifest_snapshot jsonb,
    name character varying(100),
    notes text,
    origin character varying(16) DEFAULT 'authored'::character varying NOT NULL,
    presets jsonb DEFAULT '[]'::jsonb NOT NULL,
    released_at timestamp with time zone,
    released_by uuid,
    released_by_installation uuid,
    rendering_defaults_version integer,
    revision bigint DEFAULT 1 NOT NULL,
    state character varying(16) NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    version character varying(128),
    version_id uuid DEFAULT public.epistola_uuidv7() NOT NULL,
    version_key character varying(36) DEFAULT public.epistola_version_key() NOT NULL,
    version_label text GENERATED ALWAYS AS ("substring"((version)::text, '^\d+\.\d+\.\d+-([a-z][a-z0-9-]*)\.[1-9][0-9]*$'::text)) STORED,
    version_label_number integer GENERATED ALWAYS AS (("substring"((version)::text, '^\d+\.\d+\.\d+-[a-z][a-z0-9-]*\.([1-9][0-9]*)$'::text))::integer) STORED,
    version_major integer GENERATED ALWAYS AS (("substring"((version)::text, '^(\d+)\.\d+\.\d+(?:-[a-z][a-z0-9-]*\.[1-9][0-9]*)?$'::text))::integer) STORED,
    version_minor integer GENERATED ALWAYS AS (("substring"((version)::text, '^\d+\.(\d+)\.\d+(?:-[a-z][a-z0-9-]*\.[1-9][0-9]*)?$'::text))::integer) STORED,
    version_patch integer GENERATED ALWAYS AS (("substring"((version)::text, '^\d+\.\d+\.(\d+)(?:-[a-z][a-z0-9-]*\.[1-9][0-9]*)?$'::text))::integer) STORED
);

CREATE TABLE public.catalogs (
    CONSTRAINT catalogs_exchange_publication_policy CHECK (((exchange_publication_policy)::text = ANY ((ARRAY['INHERIT'::character varying, 'ALWAYS'::character varying, 'DEFAULT_YES'::character varying, 'DEFAULT_NO'::character varying, 'NEVER'::character varying])::text[]))),
    CONSTRAINT catalogs_source_auth_type_check CHECK (((source_auth_type)::text = ANY ((ARRAY['NONE'::character varying, 'API_KEY'::character varying, 'BEARER'::character varying])::text[]))),
    CONSTRAINT catalogs_type_check CHECK (((type)::text = ANY ((ARRAY['AUTHORED'::character varying, 'SUBSCRIBED'::character varying])::text[]))),
    catalog_id uuid DEFAULT public.epistola_uuidv7() NOT NULL,
    catalog_key public.catalog_key NOT NULL,
    catalog_metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    content_updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    description text,
    exchange_publication_policy character varying(20) DEFAULT 'INHERIT'::character varying NOT NULL,
    imported_at timestamp with time zone,
    name character varying(255) NOT NULL,
    namespace text NOT NULL,
    next_version_id uuid,
    registry text NOT NULL,
    source_auth_credential text,
    source_auth_type character varying(20) DEFAULT 'NONE'::character varying,
    source_url text,
    tenant_key public.tenant_key NOT NULL,
    type character varying(20) DEFAULT 'AUTHORED'::character varying NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.changelog_acknowledgments (
    acknowledged_at timestamp with time zone DEFAULT now() NOT NULL,
    user_id uuid NOT NULL,
    version character varying(20) NOT NULL
);

CREATE TABLE public.cluster_nodes (
    capabilities jsonb DEFAULT '[]'::jsonb NOT NULL,
    joined_at timestamp with time zone DEFAULT now() NOT NULL,
    last_poll_completed_at timestamp with time zone,
    last_seen_at timestamp with time zone NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    node_id text NOT NULL,
    version text
);

CREATE TABLE public.cluster_scheduled_task_registrations (
    node_id text NOT NULL,
    task_key text NOT NULL
);

CREATE TABLE public.cluster_tasks_scheduled (
    CONSTRAINT chk_cluster_tasks_scheduled_attempt_count CHECK ((attempt_count >= 0)),
    CONSTRAINT chk_cluster_tasks_scheduled_catch_up_policy CHECK ((catch_up_policy = ANY (ARRAY['coalesce'::text, 'catch_up'::text]))),
    CONSTRAINT chk_cluster_tasks_scheduled_consecutive_failures CHECK ((consecutive_failures >= 0)),
    CONSTRAINT chk_cluster_tasks_scheduled_execution_scope CHECK ((execution_scope = ANY (ARRAY['single_owner'::text, 'each_capable_node'::text]))),
    CONSTRAINT chk_cluster_tasks_scheduled_failure_policy CHECK ((failure_policy = ANY (ARRAY['retry_same_due'::text, 'advance_on_failure'::text]))),
    CONSTRAINT chk_cluster_tasks_scheduled_interval CHECK ((((schedule_kind = 'cron'::text) AND (cron_expression IS NOT NULL) AND (interval_ms IS NULL)) OR ((schedule_kind = ANY (ARRAY['fixed_delay'::text, 'fixed_rate'::text])) AND (cron_expression IS NULL) AND (interval_ms IS NOT NULL) AND (interval_ms > 0)))),
    CONSTRAINT chk_cluster_tasks_scheduled_schedule_kind CHECK ((schedule_kind = ANY (ARRAY['cron'::text, 'fixed_delay'::text, 'fixed_rate'::text]))),
    attempt_count integer DEFAULT 0 NOT NULL,
    catch_up_policy text DEFAULT 'coalesce'::text NOT NULL,
    consecutive_failures integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    cron_expression text,
    enabled boolean DEFAULT true NOT NULL,
    execution_scope text DEFAULT 'single_owner'::text NOT NULL,
    failure_policy text DEFAULT 'retry_same_due'::text NOT NULL,
    interval_ms bigint,
    last_completed_at timestamp with time zone,
    last_error text,
    last_failed_at timestamp with time zone,
    last_started_at timestamp with time zone,
    lease_expires_at timestamp with time zone,
    lease_owner_node_id text,
    next_due_at timestamp with time zone NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    required_capability text DEFAULT 'suite'::text NOT NULL,
    routing_key text NOT NULL,
    schedule_kind text NOT NULL,
    task_key text NOT NULL,
    task_type text NOT NULL,
    tenant_key public.tenant_key,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    zone_id text DEFAULT 'UTC'::text NOT NULL
);

CREATE TABLE public.cluster_tasks_scheduled_node_state (
    CONSTRAINT chk_cluster_tasks_scheduled_node_attempt_count CHECK ((attempt_count >= 0)),
    CONSTRAINT chk_cluster_tasks_scheduled_node_consecutive_failures CHECK ((consecutive_failures >= 0)),
    attempt_count integer DEFAULT 0 NOT NULL,
    consecutive_failures integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_completed_at timestamp with time zone,
    last_error text,
    last_failed_at timestamp with time zone,
    last_started_at timestamp with time zone,
    lease_expires_at timestamp with time zone,
    next_due_at timestamp with time zone NOT NULL,
    node_id text NOT NULL,
    task_key text NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.cluster_timers (
    CONSTRAINT chk_cluster_timers_attempt_count CHECK ((attempt_count >= 0)),
    CONSTRAINT chk_cluster_timers_status CHECK ((status = ANY (ARRAY['scheduled'::text, 'running'::text, 'paused'::text]))),
    attempt_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    due_at timestamp with time zone NOT NULL,
    last_completed_at timestamp with time zone,
    last_error text,
    last_started_at timestamp with time zone,
    lease_expires_at timestamp with time zone,
    lease_owner_node_id text,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    required_capability text DEFAULT 'suite'::text NOT NULL,
    routing_key text NOT NULL,
    status text DEFAULT 'scheduled'::text NOT NULL,
    tenant_key public.tenant_key,
    timer_key text NOT NULL,
    timer_type text NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.code_list_refreshes (
    last_refresh_error text,
    last_refreshed_at timestamp with time zone,
    resource_id uuid NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    version_id uuid NOT NULL
);

CREATE TABLE public.consumer_node_assignments (
    consumer_id text NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    node_id text NOT NULL,
    partitions jsonb NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.consumer_partition_cursors (
    consumer_id text NOT NULL,
    last_acked_sequence bigint DEFAULT 0 NOT NULL,
    partition public.generation_partition NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.document_content (
    content bytea NOT NULL,
    content_type character varying(100) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    key character varying(512) NOT NULL,
    size_bytes bigint NOT NULL
)
PARTITION BY RANGE (created_at);

CREATE TABLE public.document_content_2026_06 (
    content bytea NOT NULL,
    content_type character varying(100) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    key character varying(512) NOT NULL,
    size_bytes bigint NOT NULL
);

CREATE TABLE public.document_generation_batches (
    CONSTRAINT chk_batches_total_count_positive CHECK ((total_count > 0)),
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    final_completed_count integer,
    final_failed_count integer,
    id uuid NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    total_count integer NOT NULL
);

CREATE TABLE public.document_generation_requests (
    CONSTRAINT chk_requests_release_or_draft CHECK (((release_version IS NULL) OR (draft_id IS NULL))),
    CONSTRAINT chk_requests_render_source CHECK (((version_key IS NOT NULL) OR (environment_key IS NOT NULL) OR (release_version IS NOT NULL) OR (draft_id IS NOT NULL))),
    CONSTRAINT document_generation_requests_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    batch_id uuid,
    catalog_key public.catalog_key DEFAULT 'default'::character varying NOT NULL,
    catalog_version_id text,
    claimed_at timestamp with time zone,
    claimed_by character varying(255),
    completed_at timestamp with time zone,
    correlation_id character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    data jsonb NOT NULL,
    document_key uuid,
    draft_id character varying(36),
    draft_label character varying(100),
    environment_key public.environment_key,
    error_message text,
    expires_at timestamp with time zone,
    filename character varying(255),
    id uuid NOT NULL,
    namespace text NOT NULL,
    recoveries integer DEFAULT 0 NOT NULL,
    registry text NOT NULL,
    release_version character varying(128),
    routing_key text,
    started_at timestamp with time zone,
    status character varying(20) NOT NULL,
    template_key public.template_key NOT NULL,
    template_resource_id uuid,
    tenant_key public.tenant_key NOT NULL,
    variant_key public.variant_key NOT NULL,
    version_key integer
)
PARTITION BY RANGE (created_at);

CREATE TABLE public.document_generation_requests_202606 (
    CONSTRAINT chk_requests_release_or_draft CHECK (((release_version IS NULL) OR (draft_id IS NULL))),
    CONSTRAINT chk_requests_render_source CHECK (((version_key IS NOT NULL) OR (environment_key IS NOT NULL) OR (release_version IS NOT NULL) OR (draft_id IS NOT NULL))),
    CONSTRAINT document_generation_requests_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    batch_id uuid,
    catalog_key public.catalog_key DEFAULT 'default'::character varying NOT NULL,
    catalog_version_id text,
    claimed_at timestamp with time zone,
    claimed_by character varying(255),
    completed_at timestamp with time zone,
    correlation_id character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    data jsonb NOT NULL,
    document_key uuid,
    draft_id character varying(36),
    draft_label character varying(100),
    environment_key public.environment_key,
    error_message text,
    expires_at timestamp with time zone,
    filename character varying(255),
    id uuid NOT NULL,
    namespace text NOT NULL,
    recoveries integer DEFAULT 0 NOT NULL,
    registry text NOT NULL,
    release_version character varying(128),
    routing_key text,
    started_at timestamp with time zone,
    status character varying(20) NOT NULL,
    template_key public.template_key NOT NULL,
    template_resource_id uuid,
    tenant_key public.tenant_key NOT NULL,
    variant_key public.variant_key NOT NULL,
    version_key integer
);

CREATE TABLE public.documents (
    CONSTRAINT chk_documents_filename_not_empty CHECK ((length((filename)::text) > 0)),
    CONSTRAINT chk_documents_render_source CHECK (((version_key IS NOT NULL) OR (release_version IS NOT NULL) OR (draft_id IS NOT NULL))),
    CONSTRAINT chk_documents_size_positive CHECK ((size_bytes > 0)),
    catalog_key public.catalog_key DEFAULT 'default'::character varying NOT NULL,
    catalog_version_id text,
    content_type character varying(100) DEFAULT 'application/pdf'::character varying NOT NULL,
    correlation_id character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    draft_id character varying(36),
    draft_label character varying(100),
    filename character varying(255) NOT NULL,
    id uuid NOT NULL,
    namespace text NOT NULL,
    registry text NOT NULL,
    release_version character varying(128),
    size_bytes bigint NOT NULL,
    template_key public.template_key NOT NULL,
    template_resource_id uuid,
    tenant_key public.tenant_key NOT NULL,
    variant_key public.variant_key NOT NULL,
    version_key integer
)
PARTITION BY RANGE (created_at);

CREATE TABLE public.documents_202606 (
    CONSTRAINT chk_documents_filename_not_empty CHECK ((length((filename)::text) > 0)),
    CONSTRAINT chk_documents_render_source CHECK (((version_key IS NOT NULL) OR (release_version IS NOT NULL) OR (draft_id IS NOT NULL))),
    CONSTRAINT chk_documents_size_positive CHECK ((size_bytes > 0)),
    catalog_key public.catalog_key DEFAULT 'default'::character varying NOT NULL,
    catalog_version_id text,
    content_type character varying(100) DEFAULT 'application/pdf'::character varying NOT NULL,
    correlation_id character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    draft_id character varying(36),
    draft_label character varying(100),
    filename character varying(255) NOT NULL,
    id uuid NOT NULL,
    namespace text NOT NULL,
    registry text NOT NULL,
    release_version character varying(128),
    size_bytes bigint NOT NULL,
    template_key public.template_key NOT NULL,
    template_resource_id uuid,
    tenant_key public.tenant_key NOT NULL,
    variant_key public.variant_key NOT NULL,
    version_key integer
);

CREATE TABLE public.environment_catalog_deployments (
    catalog_id uuid NOT NULL,
    deployed_at timestamp with time zone DEFAULT now() NOT NULL,
    deployed_by uuid,
    documents_requested bigint DEFAULT 0 NOT NULL,
    environment_key public.environment_key NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    last_used_at timestamp with time zone,
    target_version_id uuid NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.environment_deployment_log (
    CONSTRAINT chk_environment_deployment_log_action CHECK (((((action)::text = ANY (ARRAY[('DEPLOYED'::character varying)::text, ('UNDEPLOYED'::character varying)::text])) AND ((version IS NOT NULL) OR (draft_label IS NOT NULL))) OR (((action)::text = 'RELEASED'::text) AND (version IS NOT NULL)))),
    action character varying(20) NOT NULL,
    catalog_key public.catalog_key NOT NULL,
    changed_at timestamp with time zone DEFAULT now() NOT NULL,
    changed_by uuid,
    draft_label character varying(100),
    environment_key public.environment_key NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    namespace text NOT NULL,
    previous_version character varying(128),
    registry text NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    version character varying(128),
    version_key character varying(16)
);

CREATE TABLE public.environments (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    id public.environment_key NOT NULL,
    name character varying(100) NOT NULL,
    production boolean DEFAULT false NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid
);

CREATE TABLE public.event_log (
    entity_id character varying(255),
    event_type character varying(255) NOT NULL,
    id uuid NOT NULL,
    instance_id character varying(255),
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    payload jsonb NOT NULL,
    tenant_key public.tenant_key
)
PARTITION BY RANGE (occurred_at);

CREATE TABLE public.event_log_<month> (
    entity_id character varying(255),
    event_type character varying(255) NOT NULL,
    id uuid NOT NULL,
    instance_id character varying(255),
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    payload jsonb NOT NULL,
    tenant_key public.tenant_key
);

CREATE TABLE public.event_log_<month> (
    entity_id character varying(255),
    event_type character varying(255) NOT NULL,
    id uuid NOT NULL,
    instance_id character varying(255),
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    payload jsonb NOT NULL,
    tenant_key public.tenant_key
);

CREATE TABLE public.exchange_oauth_authorizations (
    code_verifier text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    redirect_uri text NOT NULL,
    state_hash character(64) NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.exchange_tenant_connections (
    CONSTRAINT exchange_tenant_connections_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'REAUTHORIZATION_REQUIRED'::character varying, 'BLOCKED'::character varying])::text[]))),
    access_token text,
    access_token_expires_at timestamp with time zone,
    authorization_request_endpoint text NOT NULL,
    base_url text NOT NULL,
    client_secret text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    default_namespace text,
    error_code text,
    error_detail text,
    issuer text NOT NULL,
    namespaces text[] DEFAULT ARRAY[]::text[] NOT NULL,
    oauth_application_id uuid,
    organization_name text,
    organization_slug text,
    refresh_token text,
    refresh_token_expires_at timestamp with time zone,
    scopes text[] DEFAULT ARRAY[]::text[] NOT NULL,
    status character varying(30) DEFAULT 'PENDING'::character varying NOT NULL,
    tenant_connection_id uuid,
    tenant_connection_reference text,
    tenant_key public.tenant_key NOT NULL,
    token_endpoint text NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.feature_toggles (
    enabled boolean NOT NULL,
    feature_key public.feature_key NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.feedback (
    category character varying(30) NOT NULL,
    console_logs text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid NOT NULL,
    description text NOT NULL,
    external_ref text,
    external_url text,
    id uuid NOT NULL,
    metadata jsonb,
    priority character varying(30) DEFAULT 'MEDIUM'::character varying NOT NULL,
    source_url text,
    status character varying(30) DEFAULT 'OPEN'::character varying NOT NULL,
    sync_attempts integer DEFAULT 0 NOT NULL,
    sync_status character varying(30) DEFAULT 'NOT_CONFIGURED'::character varying NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    title text NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.feedback_assets (
    content bytea NOT NULL,
    content_type character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    feedback_id uuid NOT NULL,
    filename character varying(255),
    id uuid NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.feedback_comments (
    author_email text,
    author_name text NOT NULL,
    body text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    external_comment_id text,
    feedback_id uuid NOT NULL,
    id uuid NOT NULL,
    source character varying(20) DEFAULT 'LOCAL'::character varying NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.generation_results (
    batch_id uuid,
    catalog_key text,
    catalog_version_id text,
    completed_at timestamp with time zone NOT NULL,
    content_type text,
    correlation_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    document_id uuid,
    draft_id character varying(36),
    draft_label character varying(100),
    error text,
    filename text,
    namespace text,
    partition public.generation_partition NOT NULL,
    registry text,
    release_version character varying(128),
    request_id uuid NOT NULL,
    routing_key text NOT NULL,
    sequence bigint NOT NULL,
    size_bytes bigint,
    status public.generation_result_status NOT NULL,
    template_id text,
    tenant_key public.tenant_key NOT NULL,
    variant_id text,
    version_id integer
)
PARTITION BY RANGE (created_at);

CREATE TABLE public.generation_results_<month> (
    batch_id uuid,
    catalog_key text,
    catalog_version_id text,
    completed_at timestamp with time zone NOT NULL,
    content_type text,
    correlation_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    document_id uuid,
    draft_id character varying(36),
    draft_label character varying(100),
    error text,
    filename text,
    namespace text,
    partition public.generation_partition NOT NULL,
    registry text,
    release_version character varying(128),
    request_id uuid NOT NULL,
    routing_key text NOT NULL,
    sequence bigint DEFAULT nextval('public.generation_results_sequence_seq'::regclass) NOT NULL,
    size_bytes bigint,
    status public.generation_result_status NOT NULL,
    template_id text,
    tenant_key public.tenant_key NOT NULL,
    variant_id text,
    version_id integer
);

CREATE TABLE public.generation_results_<month> (
    batch_id uuid,
    catalog_key text,
    catalog_version_id text,
    completed_at timestamp with time zone NOT NULL,
    content_type text,
    correlation_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    document_id uuid,
    draft_id character varying(36),
    draft_label character varying(100),
    error text,
    filename text,
    namespace text,
    partition public.generation_partition NOT NULL,
    registry text,
    release_version character varying(128),
    request_id uuid NOT NULL,
    routing_key text NOT NULL,
    sequence bigint DEFAULT nextval('public.generation_results_sequence_seq'::regclass) NOT NULL,
    size_bytes bigint,
    status public.generation_result_status NOT NULL,
    template_id text,
    tenant_key public.tenant_key NOT NULL,
    variant_id text,
    version_id integer
);

CREATE TABLE public.load_test_runs (
    CONSTRAINT chk_load_test_version_or_environment CHECK (((version_key IS NULL) OR (environment_key IS NULL))),
    CONSTRAINT chk_ltr_completed_count_non_negative CHECK ((completed_count >= 0)),
    CONSTRAINT chk_ltr_count_sum CHECK (((completed_count + failed_count) <= target_count)),
    CONSTRAINT chk_ltr_failed_count_non_negative CHECK ((failed_count >= 0)),
    CONSTRAINT load_test_runs_concurrency_level_check CHECK (((concurrency_level >= 1) AND (concurrency_level <= 500))),
    CONSTRAINT load_test_runs_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT load_test_runs_target_count_check CHECK (((target_count >= 1) AND (target_count <= 10000))),
    avg_response_time_ms double precision,
    batch_id uuid,
    claimed_at timestamp with time zone,
    claimed_by character varying(255),
    completed_at timestamp with time zone,
    completed_count integer DEFAULT 0 NOT NULL,
    concurrency_level integer NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    environment_key public.environment_key,
    error_summary jsonb,
    failed_count integer DEFAULT 0 NOT NULL,
    id uuid NOT NULL,
    last_progress_at timestamp with time zone,
    max_response_time_ms bigint,
    metrics jsonb,
    min_response_time_ms bigint,
    p50_response_time_ms bigint,
    p95_response_time_ms bigint,
    p99_response_time_ms bigint,
    requests_per_second double precision,
    started_at timestamp with time zone,
    status character varying(20) NOT NULL,
    success_rate_percent double precision,
    target_count integer NOT NULL,
    template_resource_id uuid NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    test_data jsonb NOT NULL,
    total_duration_ms bigint,
    variant_key public.variant_key NOT NULL,
    version_key integer
);

CREATE TABLE public.namespace_grant_sources (
    code character varying(32) NOT NULL,
    description text NOT NULL
);

CREATE TABLE public.quality_finding_comments (
    author_id uuid,
    author_name text NOT NULL,
    body text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    finding_id uuid NOT NULL,
    id uuid NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.quality_finding_ignores (
    finding_fingerprint character varying(128) NOT NULL,
    ignore_scope_urn character varying(512) NOT NULL,
    ignored_at timestamp with time zone DEFAULT now() NOT NULL,
    ignored_by uuid,
    reason text NOT NULL,
    revoked_at timestamp with time zone,
    revoked_by uuid,
    rule_id character varying(128) NOT NULL,
    source_id character varying(64) NOT NULL,
    template_resource_id uuid,
    tenant_key public.tenant_key NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.quality_findings (
    CONSTRAINT quality_findings_resolved_at_check CHECK ((((status)::text = 'RESOLVED'::text) = (resolved_at IS NOT NULL))),
    CONSTRAINT quality_findings_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'RESOLVED'::character varying])::text[]))),
    context jsonb DEFAULT '{}'::jsonb NOT NULL,
    docs_url text,
    fingerprint character varying(128) NOT NULL,
    first_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    id uuid NOT NULL,
    ignore_scope_urn character varying(512) NOT NULL,
    input_fingerprint character varying(64),
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    message text NOT NULL,
    message_code character varying(128),
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    node_ids text[] DEFAULT '{}'::text[] NOT NULL,
    path text,
    resolved_at timestamp with time zone,
    rule_id character varying(128) NOT NULL,
    severity character varying(16) NOT NULL,
    source_id character varying(64) NOT NULL,
    status character varying(16) DEFAULT 'OPEN'::character varying NOT NULL,
    subject_type character varying(32) NOT NULL,
    subject_urn character varying(512) NOT NULL,
    template_resource_id uuid NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    variant_key public.variant_key,
    version_key integer
);

CREATE TABLE public.release_resource_types (
    CONSTRAINT chk_release_resource_types_wire CHECK (((wire_type IS NULL) OR ((wire_type)::text = (resource_type)::text))),
    resource_type character varying(20) NOT NULL,
    wire_type character varying(20)
);

CREATE TABLE public.release_resources (
    CONSTRAINT chk_release_resources_variant_parent CHECK ((((resource_type)::text = 'variant'::text) = (parent_id IS NOT NULL))),
    catalog_id uuid NOT NULL,
    content_hash character(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    description text,
    fingerprint character varying(80),
    name character varying(255) NOT NULL,
    parent_id uuid,
    resource_id uuid NOT NULL,
    resource_key text NOT NULL,
    resource_type character varying(20) NOT NULL,
    revision bigint DEFAULT 1 NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid,
    version_id uuid NOT NULL
);

CREATE TABLE public.resource_binaries (
    binary_hash text NOT NULL,
    content_hash character(64) NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.resource_content_kinds (
    kind character varying(20) NOT NULL
);

CREATE TABLE public.resource_contents (
    content_hash character(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    kind character varying(20) NOT NULL,
    payload jsonb NOT NULL,
    stored_at timestamp with time zone DEFAULT now() NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.resource_credentials (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    credential text NOT NULL,
    credential_id uuid NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.resource_ready_marks (
    digest character(64) NOT NULL,
    marked_at timestamp with time zone DEFAULT now() NOT NULL,
    marked_by uuid,
    part text NOT NULL,
    resource_id uuid NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    version_id uuid NOT NULL
);

CREATE TABLE public.resource_references (
    alias character varying(50),
    content_hash character(64) NOT NULL,
    resource_key text NOT NULL,
    resource_type character varying(20) NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.tenant_backups (
    blob_count integer NOT NULL,
    build_version character varying(100) NOT NULL,
    captured_at timestamp with time zone NOT NULL,
    content bytea NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    fingerprint character varying(64) NOT NULL,
    id uuid NOT NULL,
    row_count integer NOT NULL,
    schema_stamp character varying(50) NOT NULL,
    size_bytes bigint NOT NULL,
    table_count integer NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.tenant_memberships (
    joined_at timestamp with time zone DEFAULT now() NOT NULL,
    last_synced_at timestamp with time zone,
    roles character varying(20)[] DEFAULT ARRAY['READER'::character varying] NOT NULL,
    tenant_key public.tenant_key NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE public.tenant_namespace_grants (
    authority text NOT NULL,
    granted_at timestamp with time zone DEFAULT now() NOT NULL,
    granted_by uuid,
    granted_through character varying(32) NOT NULL,
    namespace text NOT NULL,
    tenant_key public.tenant_key NOT NULL
);

CREATE TABLE public.tenants (
    CONSTRAINT tenants_default_locale_format CHECK (((default_locale IS NULL) OR ((default_locale)::text ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'::text))),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    default_locale character varying(35),
    default_theme_resource_id uuid,
    id public.tenant_key NOT NULL,
    name character varying(255) NOT NULL,
    publish_catalogs_by_default boolean DEFAULT false NOT NULL,
    revision bigint DEFAULT 1 NOT NULL
);

CREATE TABLE public.users (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    display_name character varying(255) NOT NULL,
    email character varying(320) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    external_id character varying(255) NOT NULL,
    id uuid NOT NULL,
    last_login_at timestamp with time zone,
    provider character varying(50) NOT NULL
);

CREATE TABLE public.web_session (
    creation_time bigint NOT NULL,
    expiry_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    primary_id character(36) NOT NULL,
    principal_name character varying(100),
    session_id character(36) NOT NULL
);

CREATE TABLE public.web_session_attributes (
    attribute_bytes bytea NOT NULL,
    attribute_name character varying(200) NOT NULL,
    session_primary_id character(36) NOT NULL
);

CREATE TRIGGER trg_app_metadata_updated_at BEFORE UPDATE ON public.app_metadata FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_catalog_resource_types_storage AFTER INSERT ON public.catalog_resource_types FOR EACH ROW EXECUTE FUNCTION public.add_release_resource_type();

CREATE TRIGGER trg_catalog_version_dependencies_revision AFTER INSERT OR DELETE OR UPDATE ON public.catalog_version_dependencies FOR EACH ROW EXECUTE FUNCTION public.bump_catalog_version_revision_for_dependency();

CREATE TRIGGER trg_catalog_versions_revision BEFORE UPDATE ON public.catalog_versions FOR EACH ROW EXECUTE FUNCTION public.bump_catalog_version_revision();

CREATE TRIGGER trg_catalogs_open_next_version AFTER INSERT ON public.catalogs FOR EACH ROW EXECUTE FUNCTION public.catalog_open_next_version();

CREATE TRIGGER trg_catalogs_updated_at BEFORE UPDATE ON public.catalogs FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_consumer_partition_cursors_updated_at BEFORE UPDATE ON public.consumer_partition_cursors FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_documents_template_identity BEFORE INSERT ON public.documents FOR EACH ROW EXECUTE FUNCTION public.fill_generation_template_identity();

CREATE TRIGGER trg_environment_catalog_deployments_one_draft BEFORE INSERT OR UPDATE OF target_version_id ON public.environment_catalog_deployments FOR EACH ROW EXECUTE FUNCTION public.epistola_one_deployed_draft();

CREATE TRIGGER trg_environments_revision BEFORE UPDATE ON public.environments FOR EACH ROW EXECUTE FUNCTION public.bump_content_revision();

CREATE TRIGGER trg_environments_updated_at BEFORE UPDATE ON public.environments FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_feedback_updated_at BEFORE UPDATE ON public.feedback FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_generation_requests_template_identity BEFORE INSERT ON public.document_generation_requests FOR EACH ROW EXECUTE FUNCTION public.fill_generation_template_identity();

CREATE TRIGGER trg_quality_finding_ignores_updated_at BEFORE UPDATE ON public.quality_finding_ignores FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_quality_findings_updated_at BEFORE UPDATE ON public.quality_findings FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_release_resources_draft_delete AFTER DELETE ON public.release_resources FOR EACH ROW WHEN (((old.parent_id IS NULL) AND (NOT public.release_resource_is_released((old.tenant_key)::text, old.version_id)))) EXECUTE FUNCTION public.sync_draft_resource_identity();

CREATE TRIGGER trg_release_resources_draft_identity BEFORE INSERT OR UPDATE OF resource_id, catalog_id, resource_key ON public.release_resources FOR EACH ROW WHEN (((new.parent_id IS NULL) AND public.release_resource_is_draft((new.tenant_key)::text, new.version_id))) EXECUTE FUNCTION public.sync_draft_resource_identity();

CREATE TRIGGER trg_release_resources_draft_revision BEFORE UPDATE ON public.release_resources FOR EACH ROW WHEN (public.release_resource_is_draft((old.tenant_key)::text, old.version_id)) EXECUTE FUNCTION public.bump_content_revision();

CREATE TRIGGER trg_release_resources_draft_touched AFTER DELETE ON public.release_resources FOR EACH ROW WHEN ((NOT public.release_resource_is_released((old.tenant_key)::text, old.version_id))) EXECUTE FUNCTION public.touch_catalog_content_on_delete();

CREATE TRIGGER trg_release_resources_draft_updated_at BEFORE UPDATE ON public.release_resources FOR EACH ROW WHEN (public.release_resource_is_draft((old.tenant_key)::text, old.version_id)) EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_release_resources_load_test_runs_follow_variant AFTER DELETE OR UPDATE OF content_hash ON public.release_resources FOR EACH ROW WHEN (((old.resource_type)::text = 'template'::text)) EXECUTE FUNCTION public.load_test_runs_follow_variant();

CREATE TRIGGER trg_release_resources_quality_findings_follow_variant AFTER DELETE OR UPDATE OF content_hash ON public.release_resources FOR EACH ROW WHEN (((old.resource_type)::text = 'template'::text)) EXECUTE FUNCTION public.quality_findings_follow_variant();

CREATE TRIGGER trg_release_resources_template_deleted AFTER DELETE ON public.release_resources FOR EACH ROW WHEN (((old.resource_type)::text = 'template'::text)) EXECUTE FUNCTION public.release_resource_variants_deleted();

CREATE TRIGGER trg_tenants_revision BEFORE UPDATE ON public.tenants FOR EACH ROW EXECUTE FUNCTION public.bump_content_revision();

CREATE UNIQUE INDEX idx_feedback_comments_external ON public.feedback_comments USING btree (tenant_key, external_comment_id) WHERE (external_comment_id IS NOT NULL);

CREATE UNIQUE INDEX uq_environment_catalog_deployments_version ON public.environment_catalog_deployments USING btree (tenant_key, environment_key, target_version_id);

CREATE UNIQUE INDEX uq_quality_findings_reconcile ON public.quality_findings USING btree (tenant_key, source_id, subject_urn, fingerprint);

CREATE UNIQUE INDEX uq_release_resources_entry ON public.release_resources USING btree (tenant_key, catalog_id, version_id, resource_type, resource_key) WHERE (parent_id IS NULL);

CREATE UNIQUE INDEX uq_release_resources_variant ON public.release_resources USING btree (tenant_key, version_id, parent_id, resource_key);

CREATE UNIQUE INDEX uq_resource_references ON public.resource_references USING btree (tenant_key, content_hash, COALESCE(alias, ''::character varying), resource_type, resource_key);

CREATE UNIQUE INDEX web_session_ix1 ON public.web_session USING btree (session_id);

CREATE VIEW public.catalog_current_releases AS
 SELECT DISTINCT ON (tenant_key, catalog_id) tenant_key,
    catalog_id,
    version_id,
    version,
    fingerprint,
    released_at
   FROM public.catalog_versions v
  WHERE (((state)::text = 'released'::text) AND ((origin)::text = 'authored'::text))
  ORDER BY tenant_key, catalog_id, version_major DESC NULLS LAST, version_minor DESC NULLS LAST, version_patch DESC NULLS LAST, (version_label IS NULL) DESC, (version_label COLLATE "C") DESC NULLS LAST, version_label_number DESC NULLS LAST, released_at DESC;

CREATE VIEW public.catalog_current_versions AS
 SELECT DISTINCT ON (tenant_key, catalog_id) tenant_key,
    catalog_id,
    version_id
   FROM public.catalog_versions v
  WHERE
        CASE
            WHEN (catalog_id = (NULLIF(current_setting('epistola.release_catalog'::text, true), ''::text))::uuid) THEN (((state)::text = 'released'::text) AND ((version)::text = current_setting('epistola.release_version'::text, true)))
            ELSE (((version_id)::text = current_setting('epistola.content_version'::text, true)) OR (version_id = ( SELECT c.next_version_id
               FROM public.catalogs c
              WHERE (((c.tenant_key)::text = (v.tenant_key)::text) AND (c.catalog_id = v.catalog_id)))))
        END
  ORDER BY tenant_key, catalog_id, (((version_id)::text = current_setting('epistola.content_version'::text, true)) IS TRUE) DESC;

CREATE VIEW public.catalog_installed_releases AS
 SELECT c.tenant_key,
    c.catalog_id,
    r.version_id,
    r.version,
    r.fingerprint,
    r.released_at
   FROM (public.catalogs c
     JOIN public.catalog_versions r ON ((((r.tenant_key)::text = (c.tenant_key)::text) AND (r.version_id = public.catalog_installed_release_id(c.tenant_key, c.next_version_id)))));

CREATE VIEW public.current_attributes AS
 SELECT tenant_key,
    catalog_id,
    version_id,
    version,
    resource_id,
    id,
    name,
    payload,
    created_at,
    updated_at,
    created_by,
    updated_by,
    revision,
    name AS display_name,
    COALESCE(((payload -> 'resource'::text) -> 'allowedValues'::text), '[]'::jsonb) AS allowed_values,
    (((payload -> 'resource'::text) -> 'codeListBinding'::text) ->> 'catalogKey'::text) AS code_list_alias,
    (((payload -> 'resource'::text) -> 'codeListBinding'::text) ->> 'key'::text) AS code_list_key,
        CASE
            WHEN ((((payload -> 'resource'::text) -> 'codeListBinding'::text) ->> 'key'::text) IS NOT NULL) THEN public.resolve_catalog_alias((tenant_key)::text, catalog_id, version_id, (version)::text, (((payload -> 'resource'::text) -> 'codeListBinding'::text) ->> 'catalogKey'::text))
            ELSE NULL::uuid
        END AS code_list_catalog_id
   FROM ( SELECT r.tenant_key,
            r.catalog_id,
            r.version_id,
            r.version,
            r.resource_id,
            r.resource_key AS id,
            (r.name)::text AS name,
            ( SELECT c.payload
                   FROM public.resource_contents c
                  WHERE (((c.tenant_key)::text = (r.tenant_key)::text) AND (c.content_hash = r.content_hash))) AS payload,
            r.created_at,
            r.updated_at,
            r.created_by,
            r.updated_by,
            r.revision
           FROM public.catalog_version_resources('attribute'::text) r(tenant_key, catalog_id, version_id, version, resource_type, resource_key, resource_id, content_hash, fingerprint, name, description, revision, created_at, updated_at, created_by, updated_by)) u;

CREATE VIEW public.current_code_lists AS
 SELECT tenant_key,
    catalog_id,
    version_id,
    version,
    resource_id,
    id,
    name,
    payload,
    row_description,
    created_at,
    updated_at,
    created_by,
    updated_by,
    revision,
    resource,
    name AS display_name,
    row_description AS description,
    COALESCE((resource -> 'entries'::text), '[]'::jsonb) AS entries,
    COALESCE(((payload -> 'source'::text) ->> 'type'::text), 'INLINE'::text) AS source_type,
    ((payload -> 'source'::text) ->> 'url'::text) AS source_url,
    COALESCE(((payload -> 'source'::text) ->> 'auth'::text), 'NONE'::text) AS auth_type,
    (((payload -> 'source'::text) ->> 'credential'::text))::uuid AS credential_id
   FROM ( SELECT p.tenant_key,
            p.catalog_id,
            p.version_id,
            p.version,
            p.resource_id,
            p.id,
            p.name,
            p.payload,
            p.row_description,
            p.created_at,
            p.updated_at,
            p.created_by,
            p.updated_by,
            p.revision,
            (p.payload -> 'resource'::text) AS resource
           FROM ( SELECT r.tenant_key,
                    r.catalog_id,
                    r.version_id,
                    r.version,
                    r.resource_id,
                    r.resource_key AS id,
                    (r.name)::text AS name,
                    ( SELECT c.payload
                           FROM public.resource_contents c
                          WHERE (((c.tenant_key)::text = (r.tenant_key)::text) AND (c.content_hash = r.content_hash))) AS payload,
                    r.description AS row_description,
                    r.created_at,
                    r.updated_at,
                    r.created_by,
                    r.updated_by,
                    r.revision
                   FROM public.catalog_version_resources('codeList'::text) r(tenant_key, catalog_id, version_id, version, resource_type, resource_key, resource_id, content_hash, fingerprint, name, description, revision, created_at, updated_at, created_by, updated_by)) p) u;

CREATE VIEW public.current_fonts AS
 SELECT tenant_key,
    catalog_id,
    version_id,
    version,
    resource_id,
    slug,
    name,
    payload,
    created_at,
    updated_at,
    created_by,
    updated_by,
    revision,
    ((payload -> 'resource'::text) ->> 'kind'::text) AS kind,
    COALESCE(((payload -> 'resource'::text) -> 'variants'::text), '[]'::jsonb) AS variants
   FROM ( SELECT r.tenant_key,
            r.catalog_id,
            r.version_id,
            r.version,
            r.resource_id,
            r.resource_key AS slug,
            (r.name)::text AS name,
            c.payload,
            r.created_at,
            r.updated_at,
            r.created_by,
            r.updated_by,
            r.revision
           FROM (public.catalog_version_resources('font'::text) r(tenant_key, catalog_id, version_id, version, resource_type, resource_key, resource_id, content_hash, fingerprint, name, description, revision, created_at, updated_at, created_by, updated_by)
             CROSS JOIN LATERAL ( SELECT c_1.payload
                   FROM public.resource_contents c_1
                  WHERE (((c_1.tenant_key)::text = (r.tenant_key)::text) AND (c_1.content_hash = r.content_hash))
                 OFFSET 0) c)) u;

CREATE VIEW public.current_images AS
 SELECT tenant_key,
    catalog_id,
    version_id,
    version,
    resource_id,
    id,
    name,
    payload,
    created_at,
    updated_at,
    created_by,
    updated_by,
    revision,
    ((payload -> 'resource'::text) ->> 'mediaType'::text) AS media_type,
    (((payload -> 'resource'::text) ->> 'width'::text))::integer AS width,
    (((payload -> 'resource'::text) ->> 'height'::text))::integer AS height,
    ((payload -> 'resource'::text) ->> 'contentHash'::text) AS content_hash,
    ( SELECT ac.size_bytes
           FROM public.asset_content ac
          WHERE (ac.content_hash = ((u.payload -> 'resource'::text) ->> 'contentHash'::text))) AS size_bytes
   FROM ( SELECT r.tenant_key,
            r.catalog_id,
            r.version_id,
            r.version,
            r.resource_id,
            r.resource_key AS id,
            (r.name)::text AS name,
            c.payload,
            r.created_at,
            r.updated_at,
            r.created_by,
            r.updated_by,
            r.revision
           FROM (public.catalog_version_resources('image'::text) r(tenant_key, catalog_id, version_id, version, resource_type, resource_key, resource_id, content_hash, fingerprint, name, description, revision, created_at, updated_at, created_by, updated_by)
             CROSS JOIN LATERAL ( SELECT c_1.payload
                   FROM public.resource_contents c_1
                  WHERE (((c_1.tenant_key)::text = (r.tenant_key)::text) AND (c_1.content_hash = r.content_hash))
                 OFFSET 0) c)) u;

CREATE VIEW public.current_stencils AS
 SELECT tenant_key,
    catalog_id,
    version_id,
    version,
    resource_id,
    id,
    name,
    payload,
    created_at,
    updated_at,
    created_by,
    updated_by,
    revision,
    ((payload -> 'resource'::text) ->> 'description'::text) AS description,
    COALESCE(((payload -> 'resource'::text) -> 'tags'::text), '[]'::jsonb) AS tags,
    ((payload -> 'resource'::text) -> 'content'::text) AS content,
    NULLIF(((payload -> 'resource'::text) -> 'parameterSchema'::text), 'null'::jsonb) AS parameter_schema,
    COALESCE((((payload -> 'resource'::text) ->> 'readsTemplateData'::text))::boolean, false) AS reads_template_data
   FROM ( SELECT r.tenant_key,
            r.catalog_id,
            r.version_id,
            r.version,
            r.resource_id,
            r.resource_key AS id,
            (r.name)::text AS name,
            c.payload,
            r.created_at,
            r.updated_at,
            r.created_by,
            r.updated_by,
            r.revision
           FROM (public.catalog_version_resources('stencil'::text) r(tenant_key, catalog_id, version_id, version, resource_type, resource_key, resource_id, content_hash, fingerprint, name, description, revision, created_at, updated_at, created_by, updated_by)
             CROSS JOIN LATERAL ( SELECT c_1.payload
                   FROM public.resource_contents c_1
                  WHERE (((c_1.tenant_key)::text = (r.tenant_key)::text) AND (c_1.content_hash = r.content_hash))
                 OFFSET 0) c)) u;

CREATE VIEW public.current_template_variants AS
 SELECT t.tenant_key,
    t.catalog_id,
    t.version_id,
    t.version,
    t.resource_id AS template_resource_id,
    (e.slot ->> 'key'::text) AS id,
    COALESCE(((v.payload -> 'resource'::text) ->> 'title'::text), (e.slot ->> 'title'::text), (e.slot ->> 'key'::text)) AS title,
    COALESCE(((v.payload -> 'resource'::text) ->> 'description'::text), (v.payload ->> 'description'::text), ((t.payload -> 'variantDescriptions'::text) ->> (e.slot ->> 'key'::text))) AS description,
    COALESCE(NULLIF((e.slot -> 'attributes'::text), 'null'::jsonb), '{}'::jsonb) AS attributes,
    COALESCE(((e.slot ->> 'isDefault'::text))::boolean, false) AS is_default,
    NULLIF(COALESCE(((v.payload -> 'resource'::text) -> 'metadata'::text), (e.slot -> 'metadata'::text)), 'null'::jsonb) AS metadata,
    COALESCE(v.content_hash, (m.model_hash)::bpchar) AS model_hash,
    COALESCE(((v.payload -> 'resource'::text) -> 'templateModel'::text), ((d.payload -> 'resource'::text) -> 'templateModel'::text), m.model) AS template_model,
    e."position",
    COALESCE(v.created_at, t.created_at) AS created_at,
    COALESCE(v.updated_at, t.updated_at) AS updated_at,
    COALESCE(v.created_by, t.created_by) AS created_by,
    COALESCE(v.updated_by, t.updated_by) AS updated_by,
    COALESCE(v.revision, t.revision) AS revision
   FROM ((((( SELECT r.tenant_key,
            r.catalog_id,
            r.version_id,
            r.version,
            r.resource_id,
            c.payload,
            r.created_at,
            r.updated_at,
            r.created_by,
            r.updated_by,
            r.revision
           FROM (public.catalog_version_resources('template'::text) r(tenant_key, catalog_id, version_id, version, resource_type, resource_key, resource_id, content_hash, fingerprint, name, description, revision, created_at, updated_at, created_by, updated_by)
             CROSS JOIN LATERAL ( SELECT c_1.payload
                   FROM public.resource_contents c_1
                  WHERE (((c_1.tenant_key)::text = (r.tenant_key)::text) AND (c_1.content_hash = r.content_hash))
                 OFFSET 0) c)) t
     CROSS JOIN LATERAL jsonb_array_elements(COALESCE(((t.payload -> 'resource'::text) -> 'variants'::text), '[]'::jsonb)) WITH ORDINALITY e(slot, "position"))
     LEFT JOIN LATERAL ( SELECT p.content_hash,
            c.payload,
            p.created_at,
            p.updated_at,
            p.created_by,
            p.updated_by,
            p.revision
           FROM (public.release_resources p
             JOIN public.resource_contents c ON ((((c.tenant_key)::text = (p.tenant_key)::text) AND (c.content_hash = p.content_hash))))
          WHERE (((p.tenant_key)::text = (t.tenant_key)::text) AND (p.parent_id = t.resource_id) AND (p.resource_key = (e.slot ->> 'key'::text)) AND (p.catalog_id = t.catalog_id) AND ((p.resource_type)::text = 'variant'::text) AND (p.version_id = t.version_id))
         OFFSET 0) v ON (true))
     LEFT JOIN LATERAL ( SELECT c.payload
           FROM ((jsonb_array_elements(((t.payload -> 'resource'::text) -> 'variants'::text)) x(slot)
             JOIN public.release_resources p ON ((((p.tenant_key)::text = (t.tenant_key)::text) AND (p.parent_id = t.resource_id) AND (p.resource_key = (x.slot ->> 'key'::text)) AND (p.catalog_id = t.catalog_id) AND ((p.resource_type)::text = 'variant'::text) AND (p.version_id = t.version_id))))
             JOIN public.resource_contents c ON ((((c.tenant_key)::text = (p.tenant_key)::text) AND (c.content_hash = p.content_hash))))
          WHERE (((x.slot ->> 'isDefault'::text))::boolean AND (v.content_hash IS NOT NULL) AND (((v.payload -> 'resource'::text) -> 'templateModel'::text) IS NULL))
         OFFSET 0) d ON (true))
     LEFT JOIN LATERAL ( SELECT x.model_hash,
            ( SELECT c.payload
                   FROM public.resource_contents c
                  WHERE (((c.tenant_key)::text = (t.tenant_key)::text) AND ((c.content_hash)::text = x.model_hash))) AS model
           FROM ( SELECT COALESCE(((e.slot -> 'templateModel'::text) ->> 'revisionDigest'::text), (((t.payload -> 'resource'::text) -> 'templateModel'::text) ->> 'revisionDigest'::text)) AS model_hash) x
          WHERE (v.content_hash IS NULL)
         OFFSET 0) m ON (true));

CREATE VIEW public.current_templates AS
 SELECT u.tenant_key,
    u.catalog_id,
    u.version_id,
    u.version,
    u.resource_id,
    u.id,
    u.name,
    u.content_hash,
    u.payload,
    u.created_at,
    u.updated_at,
    u.created_by,
    u.updated_by,
    u.revision,
    ((u.payload -> 'resource'::text) ->> 'description'::text) AS description,
    COALESCE((((u.payload -> 'resource'::text) ->> 'pdfaEnabled'::text))::boolean, true) AS pdfa_enabled,
    NULLIF(((u.payload -> 'resource'::text) -> 'dataModel'::text), 'null'::jsonb) AS data_model,
    COALESCE(( SELECT jsonb_agg((e.example || jsonb_build_object('id', COALESCE((e.example ->> 'key'::text), (e.example ->> 'name'::text)))) ORDER BY e.n) AS jsonb_agg
           FROM jsonb_array_elements(NULLIF(((u.payload -> 'resource'::text) -> 'dataExamples'::text), 'null'::jsonb)) WITH ORDINALITY e(example, n)), '[]'::jsonb) AS data_examples,
    ((u.payload -> 'resource'::text) ->> 'themeKey'::text) AS theme_key,
    theme.catalog_id AS theme_catalog_id,
    ( SELECT cr.resource_id
           FROM public.catalog_resources cr
          WHERE (((cr.tenant_key)::text = (u.tenant_key)::text) AND (cr.catalog_id = theme.catalog_id) AND ((cr.resource_type)::text = 'theme'::text) AND (cr.resource_key = ((u.payload -> 'resource'::text) ->> 'themeKey'::text)))) AS theme_resource_id,
    (jsonb_array_length(COALESCE(((u.payload -> 'resource'::text) -> 'variants'::text), '[]'::jsonb)))::bigint AS variant_count
   FROM (( SELECT r.tenant_key,
            r.catalog_id,
            r.version_id,
            r.version,
            r.resource_id,
            r.resource_key AS id,
            (r.name)::text AS name,
            r.content_hash,
            c.payload,
            r.created_at,
            r.updated_at,
            r.created_by,
            r.updated_by,
            r.revision
           FROM (public.catalog_version_resources('template'::text) r(tenant_key, catalog_id, version_id, version, resource_type, resource_key, resource_id, content_hash, fingerprint, name, description, revision, created_at, updated_at, created_by, updated_by)
             CROSS JOIN LATERAL ( SELECT c_1.payload
                   FROM public.resource_contents c_1
                  WHERE (((c_1.tenant_key)::text = (r.tenant_key)::text) AND (c_1.content_hash = r.content_hash))
                 OFFSET 0) c)) u
     CROSS JOIN LATERAL ( SELECT
                CASE
                    WHEN (((u.payload -> 'resource'::text) ->> 'themeKey'::text) IS NOT NULL) THEN public.resolve_catalog_alias((u.tenant_key)::text, u.catalog_id, u.version_id, (u.version)::text, ((u.payload -> 'resource'::text) ->> 'themeCatalogKey'::text))
                    ELSE NULL::uuid
                END AS catalog_id) theme);

CREATE VIEW public.current_themes AS
 SELECT tenant_key,
    catalog_id,
    version_id,
    version,
    resource_id,
    id,
    name,
    payload,
    created_at,
    updated_at,
    created_by,
    updated_by,
    revision,
    ((payload -> 'resource'::text) ->> 'description'::text) AS description,
    COALESCE(((payload -> 'resource'::text) -> 'documentStyles'::text), '{}'::jsonb) AS document_styles,
    NULLIF(((payload -> 'resource'::text) -> 'pageSettings'::text), 'null'::jsonb) AS page_settings,
    NULLIF(((payload -> 'resource'::text) -> 'blockStylePresets'::text), 'null'::jsonb) AS block_style_presets,
    (((payload -> 'resource'::text) ->> 'spacingUnit'::text))::numeric(6,2) AS spacing_unit
   FROM ( SELECT r.tenant_key,
            r.catalog_id,
            r.version_id,
            r.version,
            r.resource_id,
            r.resource_key AS id,
            (r.name)::text AS name,
            ( SELECT c.payload
                   FROM public.resource_contents c
                  WHERE (((c.tenant_key)::text = (r.tenant_key)::text) AND (c.content_hash = r.content_hash))) AS payload,
            r.created_at,
            r.updated_at,
            r.created_by,
            r.updated_by,
            r.revision
           FROM public.catalog_version_resources('theme'::text) r(tenant_key, catalog_id, version_id, version, resource_type, resource_key, resource_id, content_hash, fingerprint, name, description, revision, created_at, updated_at, created_by, updated_by)) u;

CREATE VIEW public.release_wire_resources AS
 SELECT r.tenant_key,
    r.catalog_id,
    r.version_id,
    v.version,
    r.resource_type,
    r.resource_key,
    r.resource_id,
    r.content_hash,
    r.fingerprint,
    r.name,
    r.description,
    r.revision,
    r.created_at,
    r.updated_at,
    r.created_by,
    r.updated_by
   FROM (public.release_resources r
     JOIN public.catalog_versions v ON ((((v.tenant_key)::text = (r.tenant_key)::text) AND (v.version_id = r.version_id))))
  WHERE (r.parent_id IS NULL);

CREATE VIEW public.resource_content_digests AS
 SELECT r.tenant_key,
    r.resource_id,
    p.resource_key AS part,
    public.template_variant_digest((r.content_hash)::text, (p.content_hash)::text) AS digest,
    r.version_id
   FROM (public.release_resources r
     JOIN public.release_resources p ON ((((p.tenant_key)::text = (r.tenant_key)::text) AND (p.catalog_id = r.catalog_id) AND (p.version_id = r.version_id) AND ((p.resource_type)::text = 'variant'::text) AND (p.parent_id = r.resource_id))))
  WHERE ((r.version_id IS NOT NULL) AND ((r.resource_type)::text = 'template'::text))
UNION ALL
 SELECT r.tenant_key,
    r.resource_id,
    ''::text AS part,
    (r.content_hash)::text AS digest,
    r.version_id
   FROM public.release_resources r
  WHERE ((r.version_id IS NOT NULL) AND ((r.resource_type)::text <> ALL ((ARRAY['template'::character varying, 'variant'::character varying])::text[])));
