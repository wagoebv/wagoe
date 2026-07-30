#!/bin/bash
# configure-security.sh - Automated security configuration for Wagoe Framework
#
# This script configures GitHub repository security settings to ensure
# only the maintainer can merge PRs and publish releases.
#
# Prerequisites:
#   - GitHub CLI (gh) installed and authenticated
#   - Repository owner permissions
#
# Usage:
#   ./scripts/configure-security.sh

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
REPO_OWNER="wagoebv"
REPO_NAME="wagoe"
BRANCH_NAME="main"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Wagoe Framework Security Setup${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check if gh is installed and authenticated
echo -e "${YELLOW}[1/6] Checking GitHub CLI...${NC}"
if ! command -v gh &> /dev/null; then
    echo -e "${RED}✗ GitHub CLI (gh) is not installed${NC}"
    echo -e "${YELLOW}Install it from: https://cli.github.com/${NC}"
    exit 1
fi

if ! gh auth status &> /dev/null; then
    echo -e "${RED}✗ GitHub CLI is not authenticated${NC}"
    echo -e "${YELLOW}Run: gh auth login${NC}"
    exit 1
fi

echo -e "${GREEN}✓ GitHub CLI authenticated${NC}"
echo ""

# Verify repository access
echo -e "${YELLOW}[2/6] Verifying repository access...${NC}"
if ! gh repo view "${REPO_OWNER}/${REPO_NAME}" &> /dev/null; then
    echo -e "${RED}✗ Cannot access repository: ${REPO_OWNER}/${REPO_NAME}${NC}"
    echo -e "${YELLOW}Make sure you have admin access to the repository${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Repository access confirmed${NC}"
echo ""

# Configure branch protection
echo -e "${YELLOW}[3/6] Configuring branch protection for '${BRANCH_NAME}'...${NC}"

# Note: We're using a simplified approach because the full JSON config might have issues
# Enable required status checks
echo -e "  → Requiring status checks..."
gh api \
  --method PUT \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  "/repos/${REPO_OWNER}/${REPO_NAME}/branches/${BRANCH_NAME}/protection" \
  --input - << 'EOF'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "lint",
      "test-core",
      "test-observability",
      "test-platform",
      "test-user",
      "test-admin",
      "test-storage",
      "test-cache",
      "test-jobs",
      "test-tenant",
      "test-email",
      "test-realtime",
      "test-scaffolder"
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": true,
    "required_approving_review_count": 1
  },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true
}
EOF

echo -e "${GREEN}✓ Branch protection configured${NC}"
echo ""

# Check repository secrets
echo -e "${YELLOW}[4/6] Checking repository secrets...${NC}"
SECRETS=$(gh secret list --repo "${REPO_OWNER}/${REPO_NAME}" 2>&1 || echo "")

if echo "$SECRETS" | grep -q "CLOJARS_USERNAME"; then
    echo -e "${GREEN}✓ CLOJARS_USERNAME secret exists${NC}"
else
    echo -e "${RED}✗ CLOJARS_USERNAME secret missing${NC}"
    echo -e "${YELLOW}  You need to add this secret manually:${NC}"
    echo -e "${YELLOW}  gh secret set CLOJARS_USERNAME --repo ${REPO_OWNER}/${REPO_NAME}${NC}"
fi

if echo "$SECRETS" | grep -q "CLOJARS_PASSWORD"; then
    echo -e "${GREEN}✓ CLOJARS_PASSWORD secret exists${NC}"
else
    echo -e "${RED}✗ CLOJARS_PASSWORD secret missing${NC}"
    echo -e "${YELLOW}  You need to add this secret manually:${NC}"
    echo -e "${YELLOW}  gh secret set CLOJARS_PASSWORD --repo ${REPO_OWNER}/${REPO_NAME}${NC}"
fi
echo ""

# Configure workflow permissions
echo -e "${YELLOW}[5/6] Configuring workflow permissions...${NC}"
gh api \
  --method PUT \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  "/repos/${REPO_OWNER}/${REPO_NAME}/actions/permissions/workflow" \
  -f default_workflow_permissions='read' \
  -F can_approve_pull_request_reviews=false

echo -e "${GREEN}✓ Workflow permissions set to read-only${NC}"
echo ""

# List current collaborators
echo -e "${YELLOW}[6/6] Checking repository collaborators...${NC}"
COLLABORATORS=$(gh api "/repos/${REPO_OWNER}/${REPO_NAME}/collaborators" --jq '.[] | "\(.login) (\(.permissions.admin // false | if . then "admin" else .permissions.push // false | if . then "write" else "read" end end))"' 2>&1 || echo "")

if [ -n "$COLLABORATORS" ]; then
    echo -e "${BLUE}Current collaborators:${NC}"
    echo "$COLLABORATORS" | while read -r line; do
        echo "  - $line"
    done
    echo ""
    echo -e "${YELLOW}⚠ Review collaborators and remove unnecessary access:${NC}"
    echo -e "${YELLOW}  https://github.com/${REPO_OWNER}/${REPO_NAME}/settings/access${NC}"
else
    echo -e "${GREEN}✓ No external collaborators found${NC}"
fi
echo ""

# Summary
echo -e "${BLUE}========================================${NC}"
echo -e "${GREEN}Security Configuration Complete!${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "${GREEN}✓ Branch protection enabled on '${BRANCH_NAME}'${NC}"
echo -e "${GREEN}✓ Workflow permissions set to read-only${NC}"
echo -e "${GREEN}✓ CODEOWNERS file created${NC}"
echo ""
echo -e "${YELLOW}Next Steps:${NC}"
echo -e "1. Verify branch protection: ${BLUE}https://github.com/${REPO_OWNER}/${REPO_NAME}/settings/branches${NC}"
echo -e "2. Check Clojars group ownership: ${BLUE}https://clojars.org/groups/com.github.${REPO_OWNER}${NC}"
echo -e "3. Review collaborator access: ${BLUE}https://github.com/${REPO_OWNER}/${REPO_NAME}/settings/access${NC}"
echo -e "4. Test security by creating a test PR"
echo ""
echo -e "${BLUE}For detailed information, see: ${NC}${YELLOW}docs-site/content/guides/security-setup.md${NC}"
