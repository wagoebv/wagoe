#!/bin/bash

#
# Docker Entrypoint for Boundary Framework
# Integrates with port management and provides environment setup
#

set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[BOUNDARY] $1${NC}"
}

log_success() {
    echo -e "${GREEN}[BOUNDARY] $1${NC}"
}

log_warning() {
    echo -e "${YELLOW}[BOUNDARY] $1${NC}"
}

log_error() {
    echo -e "${RED}[BOUNDARY] $1${NC}"
}

# Environment detection
detect_environment() {
    export DOCKER_CONTAINER=true
    
    if [[ "$DEV_MODE" == "true" ]]; then
        log_info "🔧 Development mode enabled"
        export NODE_ENV=development
    fi
    
    log_info "🐳 Running in Docker container"
    log_info "📍 Working directory: $(pwd)"
    log_info "👤 Running as user: $(whoami)"
}

# Port management setup
setup_port_management() {
    local http_port="${HTTP_PORT:-3000}"
    local http_host="${HTTP_HOST:-0.0.0.0}"
    
    log_info "🌐 HTTP configuration:"
    log_info "   Host: $http_host"
    log_info "   Port: $http_port"
    
    # Check if port is available (informational only)
    if command -v netstat >/dev/null 2>&1; then
        if netstat -tuln 2>/dev/null | grep -q ":$http_port "; then
            log_warning "⚠️  Port $http_port appears to be in use"
            log_info "   Boundary's port manager will handle conflicts automatically"
        else
            log_success "✅ Port $http_port appears to be available"
        fi
    fi
    
    # Set up port range environment for the application
    export HTTP_PORT="$http_port"
    export HTTP_HOST="$http_host"
}

# Database connectivity check
check_database() {
    if [[ -n "$POSTGRES_HOST" ]]; then
        local db_host="${POSTGRES_HOST:-localhost}"
        local db_port="${POSTGRES_PORT:-5432}"
        local db_name="${POSTGRES_DB:-wagoe_dev}"
        local db_user="${POSTGRES_USER:-postgres}"
        
        log_info "🗄️  Database configuration:"
        log_info "   Host: $db_host:$db_port"
        log_info "   Database: $db_name"
        log_info "   User: $db_user"
        
        log_info "⏳ Waiting for database connection..."
        
        local max_attempts=30
        local attempt=1
        
        while ! nc -z "$db_host" "$db_port" >/dev/null 2>&1; do
            if [[ $attempt -ge $max_attempts ]]; then
                log_error "❌ Database connection failed after $max_attempts attempts"
                log_error "   Host: $db_host:$db_port"
                exit 1
            fi
            
            log_info "   Attempt $attempt/$max_attempts - waiting for $db_host:$db_port..."
            sleep 2
            ((attempt++))
        done
        
        log_success "✅ Database connection established"
    else
        log_info "🗄️  No database configuration found (using SQLite or H2)"
    fi
}

# Application health check
wait_for_app_ready() {
    local http_port="${HTTP_PORT:-3000}"
    local max_attempts=30
    local attempt=1
    
    log_info "⏳ Waiting for application to be ready..."
    
    # Give the app some time to start
    sleep 5
    
    while ! curl -f "http://localhost:$http_port/health" >/dev/null 2>&1; do
        if [[ $attempt -ge $max_attempts ]]; then
            log_warning "⚠️  Application health check timeout"
            log_info "   This might be normal if the app doesn't have a /health endpoint"
            break
        fi
        
        log_info "   Health check attempt $attempt/$max_attempts..."
        sleep 2
        ((attempt++))
    done
    
    if curl -f "http://localhost:$http_port/health" >/dev/null 2>&1; then
        log_success "✅ Application is ready and healthy"
        log_success "🚀 Boundary Framework is running at http://localhost:$http_port"
    fi
}

# JVM configuration
setup_jvm() {
    local java_opts="${JAVA_OPTS:--Xmx512m -Xms128m -XX:+UseG1GC -XX:+UseContainerSupport}"
    
    log_info "☕ JVM configuration:"
    log_info "   Java version: $(java -version 2>&1 | head -n 1)"
    log_info "   JVM options: $java_opts"
    
    export JAVA_OPTS="$java_opts"
}

# Main execution
main() {
    log_info "🚀 Starting Boundary Framework..."
    
    detect_environment
    setup_jvm
    setup_port_management
    check_database
    
    # Execute the main command
    log_info "🎯 Executing: $*"
    
    if [[ "$1" == "java" ]]; then
        # Java application - run with health monitoring
        exec "$@" &
        local app_pid=$!
        
        # Wait for app to be ready in background
        wait_for_app_ready &
        
        # Wait for the main application process
        wait $app_pid
    else
        # Other commands - execute directly
        exec "$@"
    fi
}

# Handle shutdown gracefully
cleanup() {
    log_info "🛑 Shutting down gracefully..."
    # Add any cleanup logic here
    exit 0
}

trap cleanup SIGTERM SIGINT

# Execute main function with all arguments
main "$@"