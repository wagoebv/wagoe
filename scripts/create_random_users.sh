#!/usr/bin/env zsh

# Script to create 10 random users and retrieve them using the Wagoe API
# This assumes the HTTP server is already running

set -e

# Configuration
API_BASE_URL="${API_BASE_URL:-http://localhost:3000}"
API_VERSION="v1"

# Admin credentials for authentication (required)
if [ -z "$ADMIN_EMAIL" ] || [ -z "$ADMIN_PASSWORD" ]; then
  echo "ERROR: ADMIN_EMAIL and ADMIN_PASSWORD must be set in the environment for authentication."
  exit 1
fi

COOKIES_FILE="${COOKIES_FILE:-/tmp/wagoe-random-users.cookies}"

echo "========================================="
echo "Creating 10 Random Users"
echo "========================================="
echo "API Base URL: $API_BASE_URL"
echo "Using admin: $ADMIN_EMAIL"
echo ""

echo "Authenticating as admin..."
LOGIN_RESPONSE=$(curl -s -w "\n%{http_code}" -c "$COOKIES_FILE" -X POST \
  "${API_BASE_URL}/web/login" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "email=${ADMIN_EMAIL}" \
  --data-urlencode "password=${ADMIN_PASSWORD}")

LOGIN_CODE=$(echo "$LOGIN_RESPONSE" | tail -n1)
LOGIN_BODY=$(echo "$LOGIN_RESPONSE" | sed '$d')

if [ "$LOGIN_CODE" != "302" ]; then
  echo "✗ Login failed (HTTP $LOGIN_CODE)"
  echo "Response: $LOGIN_BODY"
  exit 1
fi

echo "✓ Authenticated, session cookie stored in $COOKIES_FILE"
echo ""

# Array of random names for variety
FIRST_NAMES=("Thijs" "Armando" "Alice" "Bob" "Charlie" "Diana" "Eve" "Frank" "Grace" "Henry" "Ivy" "Jack"
  "Liam" "Noah" "Oliver" "Elijah" "James" "William" "Benjamin" "Lucas" "Henry" "Alexander"
  "Mason" "Michael" "Ethan" "Daniel" "Jacob" "Logan" "Jackson" "Levi" "Sebastian" "Mateo"
  "Jack" "Owen" "Theodore" "Aiden" "Samuel" "Joseph" "John" "David" "Wyatt" "Matthew"
  "Luke" "Asher" "Carter" "Julian" "Grayson" "Leo" "Jayden" "Gabriel" "Isaac" "Lincoln"
  "Anthony" "Hudson" "Dylan" "Ezra" "Thomas" "Charles" "Christopher" "Jaxon" "Maverick" "Josiah"
  "Elias" "Isaiah" "Andrew" "Joshua" "Nathan" "Adrian" "Ryan" "Miles" "Eli" "Nolan"
  "Christian" "Aaron" "Cameron" "Ezekiel" "Colton" "Luca" "Landon" "Hunter" "Jonathan" "Santiago"
  "Axel" "Easton" "Cooper" "Jeremiah" "Angel" "Roman" "Connor" "Jameson" "Robert" "Greyson"
  "Jordan" "Ian" "Carson" "Jaxson" "Leonardo" "Nicholas" "Dominic" "Austin" "Everett" "Brooks"
  "Xavier" "Kai" "Jose" "Parker" "Adam" "Finn" "Evan" "Elias" "Tyler" "Diego")
LAST_NAMES=("Smith" "Johnson" "Williams" "Brown" "Jones" "Garcia" "Miller" "Davis" "Rodriguez" "Martinez"
  "Adams" "Alberts" "Andersen" "Armstrong" "Bakker" "Barrett" "Becker" "Bergman" "Black" "Blevins"
  "Blom" "Bowen" "Boyd" "Braun" "Brooks" "Brown" "Bryant" "Carlson" "Carter" "Clark"
  "Coleman" "Collins" "Cooper" "Cruz" "Daniels" "Davies" "Dawson" "Dekker" "Dijkstra" "Dixon"
  "Douglas" "Edwards" "Elliott" "Ellis" "Eriksen" "Evans" "Fischer" "Fletcher" "Ford" "Foster"
  "García" "Gardner" "Grant" "Gray" "Greene" "Groen" "Hall" "Hansen" "Harris" "Hayes"
  "Hendriks" "Hill" "Holmes" "Howard" "Hughes" "Jacobs" "Jensen" "Johnson" "Jones" "Jansen"
  "Karlsson" "Keller" "Kennedy" "Kim" "Klein" "Kramer" "Lambert" "Larsson" "Lewis" "Long"
  "López" "Martin" "Martínez" "Mason" "Meijer" "Meyer" "Mitchell" "Moore" "Morgan" "Morris"
  "Murphy" "Nelson" "Nguyen" "O'Connor" "O'Donnell" "Olsen" "Patel" "Peters" "Porter" "Reynolds"
  "Richards" "Roberts" "Robinson" "Sanders" "Schmidt" "Scott" "Smit" "Stevens" "Thompson" "van Dijk")

# Create 100 users
echo "Creating users..."
for i in {1..100}; do
  FIRST=${FIRST_NAMES[$((i-1))]}
  LAST=${LAST_NAMES[$((i-1))]}
  EMAIL=$(echo "${FIRST}.${LAST}@example.com" | tr '[:upper:]' '[:lower:]')
  
  echo "  [$i/100] Creating ${FIRST} ${LAST} (${EMAIL})..."
  
  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "${API_BASE_URL}/api/${API_VERSION}/users" \
    -b "$COOKIES_FILE" \
    -H "Content-Type: application/json" \
    -d "{
      \"email\": \"${EMAIL}\",
      \"name\": \"${FIRST} ${LAST}\",
      \"password\": \"password${i}\",
      \"role\": \"user\",
      \"active\": true
    }")
  
  HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
  BODY=$(echo "$RESPONSE" | sed '$d')
  
  if [ "$HTTP_CODE" = "201" ]; then
    echo "    ✓ Created successfully"
  else
    echo "    ✗ Failed (HTTP $HTTP_CODE)"
    echo "    Response: $BODY"
  fi
done

echo ""
echo "========================================="
echo "Retrieving Created Users"
echo "========================================="

RESPONSE=$(curl -s -w "\n%{http_code}" -X GET \
  -b "$COOKIES_FILE" \
  "${API_BASE_URL}/api/${API_VERSION}/users?limit=200")

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
  echo "✓ Successfully retrieved users"
  echo ""
  echo "$BODY" | jq '.'
else
  echo "✗ Failed to retrieve users (HTTP $HTTP_CODE)"
  echo "Response: $BODY"
fi
echo ""
echo "========================================="
echo "Summary"
echo "========================================="
echo "You can retrieve these users again with (reusing the same session cookie):"
echo "  curl -s -b '$COOKIES_FILE' '${API_BASE_URL}/api/${API_VERSION}/users?limit=20' | jq '.'"
echo "========================================="
