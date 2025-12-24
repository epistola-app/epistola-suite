#!/usr/bin/env node
/**
 * Syncs GitHub Project configuration from .github/project.yml
 * Uses gh CLI for GraphQL API calls
 *
 * Required environment variable: GH_TOKEN (PAT with project scope)
 */
import { readFileSync } from 'fs';
import { execSync } from 'child_process';
import { parse } from 'yaml';

// Load configuration
const config = parse(readFileSync('.github/project.yml', 'utf8'));

/**
 * Execute a GraphQL query using gh CLI
 */
function graphql(query, variables = {}) {
  const variableArgs = Object.entries(variables)
    .map(([key, value]) => `-f ${key}=${JSON.stringify(value)}`)
    .join(' ');

  const cmd = `gh api graphql -f query='${query.replace(/'/g, "'\\''")}' ${variableArgs}`;

  try {
    const result = execSync(cmd, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
    const parsed = JSON.parse(result);
    if (parsed.errors) {
      console.error('GraphQL errors:', JSON.stringify(parsed.errors, null, 2));
      throw new Error(parsed.errors[0].message);
    }
    return parsed.data;
  } catch (error) {
    if (error.stdout) {
      console.error('GraphQL response:', error.stdout);
    }
    throw error;
  }
}

/**
 * Find an organization's project by title
 */
async function findProject(owner, title) {
  const query = `
    query($owner: String!, $first: Int!) {
      organization(login: $owner) {
        projectsV2(first: $first) {
          nodes {
            id
            title
          }
        }
      }
    }
  `;

  const data = graphql(query, { owner, first: '20' });
  const project = data.organization?.projectsV2?.nodes?.find((p) => p.title === title);
  return project?.id || null;
}

/**
 * Create a new organization project
 */
async function createProject(projectConfig) {
  // First get the organization ID
  const orgQuery = `
    query($owner: String!) {
      organization(login: $owner) {
        id
      }
    }
  `;

  const orgData = graphql(orgQuery, { owner: projectConfig.owner });
  const ownerId = orgData.organization.id;

  // Create the project
  const createQuery = `
    mutation($ownerId: ID!, $title: String!) {
      createProjectV2(input: { ownerId: $ownerId, title: $title }) {
        projectV2 {
          id
        }
      }
    }
  `;

  const createData = graphql(createQuery, { ownerId, title: projectConfig.title });
  return createData.createProjectV2.projectV2.id;
}

/**
 * Get all fields from a project
 */
async function getProjectFields(projectId) {
  const query = `
    query($projectId: ID!) {
      node(id: $projectId) {
        ... on ProjectV2 {
          fields(first: 50) {
            nodes {
              ... on ProjectV2Field {
                id
                name
                dataType
              }
              ... on ProjectV2SingleSelectField {
                id
                name
                dataType
                options {
                  id
                  name
                  color
                  description
                }
              }
              ... on ProjectV2IterationField {
                id
                name
                dataType
              }
            }
          }
        }
      }
    }
  `;

  const data = graphql(query, { projectId });
  return data.node.fields.nodes;
}

/**
 * Create a new single select field
 */
async function createSingleSelectField(projectId, fieldConfig) {
  const query = `
    mutation($projectId: ID!, $name: String!) {
      createProjectV2Field(input: {
        projectId: $projectId,
        dataType: SINGLE_SELECT,
        name: $name
      }) {
        projectV2Field {
          ... on ProjectV2SingleSelectField {
            id
          }
        }
      }
    }
  `;

  const data = graphql(query, { projectId, name: fieldConfig.name });
  return data.createProjectV2Field.projectV2Field.id;
}

/**
 * Create a date field
 */
async function createDateField(projectId, fieldConfig) {
  const query = `
    mutation($projectId: ID!, $name: String!) {
      createProjectV2Field(input: {
        projectId: $projectId,
        dataType: DATE,
        name: $name
      }) {
        projectV2Field {
          ... on ProjectV2Field {
            id
          }
        }
      }
    }
  `;

  const data = graphql(query, { projectId, name: fieldConfig.name });
  return data.createProjectV2Field.projectV2Field.id;
}

/**
 * Create a text field
 */
async function createTextField(projectId, fieldConfig) {
  const query = `
    mutation($projectId: ID!, $name: String!) {
      createProjectV2Field(input: {
        projectId: $projectId,
        dataType: TEXT,
        name: $name
      }) {
        projectV2Field {
          ... on ProjectV2Field {
            id
          }
        }
      }
    }
  `;

  const data = graphql(query, { projectId, name: fieldConfig.name });
  return data.createProjectV2Field.projectV2Field.id;
}

/**
 * Update a single select field's options
 */
async function updateSingleSelectOptions(projectId, fieldId, options, existingOptions) {
  // Create missing options
  for (const option of options) {
    const existing = existingOptions?.find((o) => o.name === option.name);
    if (!existing) {
      console.log(`  Creating option: ${option.name}`);
      const query = `
        mutation($projectId: ID!, $fieldId: ID!, $name: String!, $color: ProjectV2SingleSelectFieldOptionColor!, $description: String!) {
          createProjectV2FieldOption(input: {
            projectId: $projectId,
            fieldId: $fieldId,
            name: $name,
            color: $color,
            description: $description
          }) {
            projectV2SingleSelectFieldOption {
              id
            }
          }
        }
      `;

      graphql(query, {
        projectId,
        fieldId,
        name: option.name,
        color: option.color || 'GRAY',
        description: option.description || '',
      });
    }
  }
}

/**
 * Sync a single field
 */
async function syncField(projectId, fieldConfig, existingFields) {
  const existing = existingFields.find((f) => f.name === fieldConfig.name);

  if (existing) {
    console.log(`Field "${fieldConfig.name}" exists`);

    // Update options for single select fields
    if (fieldConfig.type === 'single_select' && fieldConfig.options) {
      await updateSingleSelectOptions(projectId, existing.id, fieldConfig.options, existing.options);
    }
  } else {
    console.log(`Creating field: ${fieldConfig.name}`);

    switch (fieldConfig.type) {
      case 'single_select':
        const fieldId = await createSingleSelectField(projectId, fieldConfig);
        if (fieldConfig.options) {
          await updateSingleSelectOptions(projectId, fieldId, fieldConfig.options, []);
        }
        break;
      case 'date':
        await createDateField(projectId, fieldConfig);
        break;
      case 'text':
        await createTextField(projectId, fieldConfig);
        break;
      default:
        console.warn(`Unknown field type: ${fieldConfig.type}`);
    }
  }
}

/**
 * Link a repository to the project
 */
async function linkRepository(projectId, owner, repoName) {
  // Get repository ID
  const repoQuery = `
    query($owner: String!, $name: String!) {
      repository(owner: $owner, name: $name) {
        id
      }
    }
  `;

  const repoData = graphql(repoQuery, { owner, name: repoName });
  const repoId = repoData.repository?.id;

  if (!repoId) {
    console.warn(`Repository not found: ${owner}/${repoName}`);
    return;
  }

  // Link repository to project
  const linkQuery = `
    mutation($projectId: ID!, $repositoryId: ID!) {
      linkProjectV2ToRepository(input: {
        projectId: $projectId,
        repositoryId: $repositoryId
      }) {
        repository {
          id
        }
      }
    }
  `;

  try {
    graphql(linkQuery, { projectId, repositoryId: repoId });
    console.log(`Linked repository: ${owner}/${repoName}`);
  } catch (error) {
    // Ignore if already linked
    if (!error.message?.includes('already linked')) {
      throw error;
    }
    console.log(`Repository already linked: ${owner}/${repoName}`);
  }
}

/**
 * Main sync function
 */
async function main() {
  const { project, fields } = config;

  console.log('=== GitHub Project Sync ===\n');
  console.log(`Project: ${project.title}`);
  console.log(`Owner: ${project.owner}\n`);

  // 1. Find or create project
  let projectId = await findProject(project.owner, project.title);

  if (!projectId) {
    console.log('Creating project...');
    projectId = await createProject(project);
    console.log(`Created project: ${projectId}\n`);
  } else {
    console.log(`Found existing project: ${projectId}\n`);
  }

  // 2. Get existing fields
  console.log('Syncing fields...');
  const existingFields = await getProjectFields(projectId);

  // 3. Sync each field
  for (const field of fields) {
    await syncField(projectId, field, existingFields);
  }

  // 4. Link repositories
  console.log('\nLinking repositories...');
  for (const repo of project.repositories || []) {
    await linkRepository(projectId, project.owner, repo);
  }

  console.log('\n=== Sync complete! ===');
}

main().catch((error) => {
  console.error('Sync failed:', error.message);
  process.exit(1);
});
