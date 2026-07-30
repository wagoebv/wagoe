#!/bin/bash
# verify-security.sh - Verify security configuration for Wagoe Framework
#
# This script checks that all security measures are properly configured.
#
# Prerequisites:
#   - GitHub CLI (gh) installed and authenticated
#
# Usage:
#   ./scripts/verify-security.sh

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
echo -e "${BLUE}Wagoe Framework Security Verification${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0

# Check GitHub CLI
echo -e "${YELLOW}[1/8] Checking GitHub CLI authentication...${NC}"
if gh auth status &> /dev/null; then
    echo -e "${GREEN}✓ GitHub CLI authenticated${NC}"
    ((PASS_COUNT++))
else
    echo -e "${RED}✗ GitHub CLI not authenticated${NC}"
    echo -e "${YELLOW}  Run: gh auth login${NC}"
    ((FAIL_COUNT++))
    exit 1
fi
echo ""

# Check branch protection
echo -e "${YELLOW}[2/8] Checking branch protection...${NC}"
PROTECTION=$(gh api "/repos/${REPO_OWNER}/${REPO_NAME}/branches/${BRANCH_NAME}/protection" 2>&1 || echo "NOT_FOUND")

if echo "$PROTECTION" | grep -q "NOT_FOUND\|Not Found"; then
    echo -e "${RED}✗ Branch protection not configured${NC}"
    ((FAIL_COUNT++))
else
    echo -e "${GREEN}✓ Branch protection enabled${NC}"
    ((PASS_COUNT++))
    
    # Check required status checks
    if echo "$PROTECTION" | grep -q "required_status_checks"; then
        echo -e "${GREEN}  ✓ Required status checks enabled${NC}"
    else
        echo -e "${YELLOW}  ⚠ Required status checks not enabled${NC}"
        ((WARN_COUNT++))
    fi
    
    # Check required reviews
    if echo "$PROTECTION" | grep -q "required_pull_request_reviews"; then
        echo -e "${GREEN}  ✓ Required PR reviews enabled${NC}"
    else
        echo -e "${YELLOW}  ⚠ Required PR reviews not enabled${NC}"
        ((WARN_COUNT++))
    fi
fi
echo ""

# Check workflow permissions
echo -e "${YELLOW}[3/8] Checking workflow permissions...${NC}"
WORKFLOW_PERMS=$(gh api "/repos/${REPO_OWNER}/${REPO_NAME}/actions/permissions/workflow" 2>&1 || echo "")

if echo "$WORKFLOW_PERMS" | grep -q '"default_workflow_permissions":"read"'; then
    echo -e "${GREEN}✓ Workflow permissions set to read-only${NC}"
    ((PASS_COUNT++))
else
    echo -e "${YELLOW}⚠ Workflow permissions not set to read-only${NC}"
    echo -e "${YELLOW}  Workflows may have write access${NC}"
    ((WARN_COUNT++))
fi
echo ""

# Check repository secrets
echo -e "${YELLOW}[4/8] Checking repository secrets...${NC}"
SECRETS=$(gh secret list --repo "${REPO_OWNER}/${REPO_NAME}" 2>&1 || echo "")

if echo "$SECRETS" | grep -q "CLOJARS_USERNAME"; then
    echo -e "${GREEN}✓ CLOJARS_USERNAME secret exists${NC}"
    ((PASS_COUNT++))
else
    echo -e "${RED}✗ CLOJARS_USERNAME secret missing${NC}"
    ((FAIL_COUNT++))
fi

if echo "$SECRETS" | grep -q "CLOJARS_PASSWORD"; then
    echo -e "${GREEN}✓ CLOJARS_PASSWORD secret exists${NC}"
    ((PASS_COUNT++))
else
    echo -e "${RED}✗ CLOJARS_PASSWORD secret missing${NC}"
    ((FAIL_COUNT++))
fi
echo ""

# Check CODEOWNERS file
echo -e "${YELLOW}[5/8] Checking CODEOWNERS file...${NC}"
if [ -f ".github/CODEOWNERS" ]; then
    echo -e "${GREEN}✓ CODEOWNERS file exists${NC}"
    ((PASS_COUNT++))
else
    echo -e "${YELLOW}⚠ CODEOWNERS file not found${NC}"
    echo -e "${YELLOW}  Create it at: .github/CODEOWNERS${NC}"
    ((WARN_COUNT++))
fi
echo ""

# Check collaborators
echo -e "${YELLOW}[6/8] Checking repository collaborators...${NC}"
COLLABORATORS=$(gh api "/repos/${REPO_OWNER}/${REPO_NAME}/collaborators" --jq 'length' 2>&1 || echo "0")

if [ "$COLLABORATORS" -eq 0 ]; then
    echo -e "${GREEN}✓ No external collaborators (most secure)${NC}"
    ((PASS_COUNT++))
else
    echo -e "${YELLOW}⚠ ${COLLABORATORS} collaborator(s) found${NC}"
    echo -e "${YELLOW}  Review access: https://github.com/${REPO_OWNER}/${REPO_NAME}/settings/access${NC}"
    ((WARN_COUNT++))
fi
echo ""

# Check if repository is public
echo -e "${YELLOW}[7/8] Checking repository visibility...${NC}"
VISIBILITY=$(gh repo view "${REPO_OWNER}/${REPO_NAME}" --json visibility --jq '.visibility' 2>&1 || echo "unknown")

if [ "$VISIBILITY" = "PUBLIC" ]; then
    echo -e "${GREEN}✓ Repository is public${NC}"
    echo -e "${YELLOW}  Note: Anyone can fork and submit PRs${NC}"
    ((PASS_COUNT++))
elif [ "$VISIBILITY" = "PRIVATE" ]; then
    echo -e "${GREEN}✓ Repository is private${NC}"
    echo -e "${YELLOW}  Note: Only invited collaborators can access${NC}"
    ((PASS_COUNT++))
else
    echo -e "${YELLOW}⚠ Repository visibility: ${VISIBILITY}${NC}"
    ((WARN_COUNT++))
fi
echo ""

# Check recent workflow runs
echo -e "${YELLOW}[8/8] Checking recent workflow runs...${NC}"
PUBLISH_RUNS=$(gh run list --workflow=publish.yml --limit 5 --json conclusion,headBranch,event,createdAt 2>&1 || echo "[]")

if echo "$PUBLISH_RUNS" | grep -q "\[\]"; then
    echo -e "${BLUE}ℹ No recent publish workflow runs${NC}"
else
    echo -e "${BLUE}Recent publish workflow runs:${NC}"
    echo "$PUBLISH_RUNS" | jq -r '.[] | "  - \(.event) on \(.headBranch): \(.conclusion) (\(.createdAt))"' 2>/dev/null || echo "  (Unable to parse)"
fi
echo ""

# Summary
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Verification Summary${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "${GREEN}✓ Passed: ${PASS_COUNT}${NC}"
echo -e "${YELLOW}⚠ Warnings: ${WARN_COUNT}${NC}"
echo -e "${RED}✗ Failed: ${FAIL_COUNT}${NC}"
echo ""

if [ $FAIL_COUNT -eq 0 ] && [ $WARN_COUNT -eq 0 ]; then
    echo -e "${GREEN}🎉 All security checks passed!${NC}"
    echo -e "${GREEN}Your repository is properly secured.${NC}"
    exit 0
elif [ $FAIL_COUNT -eq 0 ]; then
    echo -e "${YELLOW}⚠ Security is mostly configured, but review warnings above.${NC}"
    echo -e "${YELLOW}See: docs-site/content/guides/security-setup.md for details${NC}"
    exit 0
else
    echo -e "${RED}✗ Security configuration incomplete.${NC}"
    echo -e "${YELLOW}Run: ./scripts/configure-security.sh${NC}"
    echo -e "${YELLOW}See: docs-site/content/guides/security-setup.md for manual steps${NC}"
    exit 1
fi
