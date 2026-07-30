#!/bin/bash

#
# Wagoe Framework - Docker Development Helper
# Provides convenient commands for Docker-based development
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default configuration
DEFAULT_PROFILE="dev"
DEFAULT_HTTP_PORT="3000"
DEFAULT_DOCS_PORT="8080"

show_help() {
    cat << EOF
🐳 Wagoe Framework - Docker Development Helper

USAGE:
    $0 [COMMAND] [OPTIONS]

COMMANDS:
    start [PROFILE]     Start development environment (default: dev)
    stop               Stop all services
    restart [PROFILE]  Restart development environment
    logs [SERVICE]     Show logs for service (default: all)
    shell [SERVICE]    Open shell in service container
    clean             Clean up containers, volumes, and images
    status            Show status of all services
    port-check        Check port availability
    build             Build application images
    test              Run tests in container environment

PROFILES:
    dev               Basic development (app + db)
    docs              Development + documentation server
    full              All services including dev tools
    
OPTIONS:
    --port PORT       Override HTTP port (default: $DEFAULT_HTTP_PORT)
    --docs-port PORT  Override docs port (default: $DEFAULT_DOCS_PORT)  
    --clean           Clean build before starting
    --detach          Run in background (detached mode)
    --help            Show this help message

EXAMPLES:
    $0 start                    # Start basic development environment
    $0 start docs --port 3001   # Start with docs, app on port 3001
    $0 start full --clean       # Clean build and start all services
    $0 logs app                 # Show application logs
    $0 shell dev-tools          # Open shell in dev-tools container
    $0 port-check               # Check if ports are available
    $0 clean                    # Clean up everything

ENVIRONMENT:
    Copy .env.example to .env and customize as needed
    Create docker-compose.override.yml for local customizations

EOF
}

log_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

log_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

log_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

log_error() {
    echo -e "${RED}❌ $1${NC}"
}

check_requirements() {
    log_info "Checking requirements..."
    
    if ! command -v docker &> /dev/null; then
        log_error "Docker is required but not installed"
        exit 1
    fi
    
    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
        log_error "Docker Compose is required but not installed"
        exit 1
    fi
    
    # Use docker compose (new) or docker-compose (legacy)
    if docker compose version &> /dev/null; then
        DOCKER_COMPOSE="docker compose"
    else
        DOCKER_COMPOSE="docker-compose"
    fi
    
    log_success "Requirements check passed"
}

setup_environment() {
    log_info "Setting up environment..."
    
    cd "$PROJECT_ROOT"
    
    # Create .env from example if it doesn't exist
    if [[ ! -f .env ]]; then
        if [[ -f .env.example ]]; then
            cp .env.example .env
            log_success "Created .env from .env.example"
            log_warning "Please review and customize .env file"
        else
            log_warning ".env.example not found, using Docker defaults"
        fi
    fi
    
    # Apply port overrides if specified
    if [[ -n "$HTTP_PORT_OVERRIDE" ]]; then
        export HTTP_PORT="$HTTP_PORT_OVERRIDE"
        log_info "Using custom HTTP port: $HTTP_PORT"
    fi
    
    if [[ -n "$DOCS_PORT_OVERRIDE" ]]; then
        export DOCS_PORT="$DOCS_PORT_OVERRIDE"  
        log_info "Using custom docs port: $DOCS_PORT"
    fi
}

check_ports() {
    log_info "Checking port availability..."
    
    local http_port="${HTTP_PORT:-$DEFAULT_HTTP_PORT}"
    local docs_port="${DOCS_PORT:-$DEFAULT_DOCS_PORT}"
    local postgres_port="${POSTGRES_PORT:-5432}"
    
    local ports_to_check=("$http_port" "$postgres_port")
    
    if [[ "$PROFILE" == "docs" || "$PROFILE" == "full" ]]; then
        ports_to_check+=("$docs_port")
    fi
    
    local busy_ports=()
    
    for port in "${ports_to_check[@]}"; do
        if netstat -tuln 2>/dev/null | grep -q ":$port " || \
           ss -tuln 2>/dev/null | grep -q ":$port " || \
           lsof -i ":$port" 2>/dev/null | grep -q LISTEN; then
            busy_ports+=("$port")
        fi
    done
    
    if [[ ${#busy_ports[@]} -gt 0 ]]; then
        log_warning "The following ports are busy: ${busy_ports[*]}"
        log_info "Docker will handle port conflicts automatically"
        log_info "Or use --port and --docs-port to override"
    else
        log_success "All required ports are available"
    fi
}

start_services() {
    local profile="${1:-$DEFAULT_PROFILE}"
    
    log_info "Starting Wagoe Framework ($profile profile)..."
    
    local compose_args=""
    
    if [[ "$CLEAN_BUILD" == "true" ]]; then
        log_info "Clean build requested, removing existing images..."
        $DOCKER_COMPOSE down --rmi local --volumes || true
    fi
    
    if [[ "$DETACHED" == "true" ]]; then
        compose_args="$compose_args -d"
    fi
    
    case "$profile" in
        "dev")
            $DOCKER_COMPOSE up $compose_args app db
            ;;
        "docs")
            $DOCKER_COMPOSE --profile docs up $compose_args app db docs
            ;;
        "full")
            $DOCKER_COMPOSE --profile full up $compose_args
            ;;
        *)
            log_error "Unknown profile: $profile"
            log_info "Available profiles: dev, docs, full"
            exit 1
            ;;
    esac
    
    if [[ "$DETACHED" == "true" ]]; then
        log_success "Services started in background"
        show_status
    else
        log_success "Services started"
    fi
}

stop_services() {
    log_info "Stopping all services..."
    $DOCKER_COMPOSE down
    log_success "Services stopped"
}

restart_services() {
    local profile="${1:-$DEFAULT_PROFILE}"
    log_info "Restarting services..."
    stop_services
    start_services "$profile"
}

show_logs() {
    local service="${1:-}"
    
    if [[ -n "$service" ]]; then
        log_info "Showing logs for service: $service"
        $DOCKER_COMPOSE logs -f "$service"
    else
        log_info "Showing logs for all services"
        $DOCKER_COMPOSE logs -f
    fi
}

open_shell() {
    local service="${1:-app}"
    
    log_info "Opening shell in $service container..."
    
    if ! $DOCKER_COMPOSE ps "$service" | grep -q "Up"; then
        log_error "Service $service is not running"
        exit 1
    fi
    
    $DOCKER_COMPOSE exec "$service" /bin/bash || \
    $DOCKER_COMPOSE exec "$service" /bin/sh
}

clean_up() {
    log_warning "This will remove all containers, volumes, and images for this project"
    read -p "Are you sure? (y/N): " -n 1 -r
    echo
    
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        log_info "Cleaning up Docker resources..."
        $DOCKER_COMPOSE down --rmi all --volumes --remove-orphans
        docker volume prune -f
        log_success "Clean up completed"
    else
        log_info "Clean up cancelled"
    fi
}

show_status() {
    log_info "Service status:"
    $DOCKER_COMPOSE ps
    
    echo
    log_info "Network status:"
    docker network ls | grep wagoe || log_warning "No wagoe networks found"
    
    echo  
    log_info "Volume status:"
    docker volume ls | grep wagoe || log_warning "No wagoe volumes found"
}

build_images() {
    log_info "Building application images..."
    $DOCKER_COMPOSE build
    log_success "Images built successfully"
}

run_tests() {
    log_info "Running tests in container environment..."
    
    # Start test dependencies
    $DOCKER_COMPOSE up -d db
    
    # Wait for database to be ready
    log_info "Waiting for database to be ready..."
    until $DOCKER_COMPOSE exec db pg_isready -U postgres; do
        sleep 2
    done
    
    # Run tests
    $DOCKER_COMPOSE run --rm dev-tools clojure -X:test
    
    log_success "Tests completed"
}

# Parse command line arguments
COMMAND=""
PROFILE="$DEFAULT_PROFILE"
HTTP_PORT_OVERRIDE=""
DOCS_PORT_OVERRIDE=""
CLEAN_BUILD="false"
DETACHED="false"

while [[ $# -gt 0 ]]; do
    case $1 in
        start|stop|restart|logs|shell|clean|status|port-check|build|test)
            COMMAND="$1"
            shift
            ;;
        --port)
            HTTP_PORT_OVERRIDE="$2"
            shift 2
            ;;
        --docs-port)
            DOCS_PORT_OVERRIDE="$2"
            shift 2
            ;;
        --clean)
            CLEAN_BUILD="true" 
            shift
            ;;
        --detach|-d)
            DETACHED="true"
            shift
            ;;
        --help|-h)
            show_help
            exit 0
            ;;
        dev|docs|full)
            PROFILE="$1"
            shift
            ;;
        *)
            if [[ -z "$COMMAND" ]]; then
                COMMAND="$1"
            elif [[ "$COMMAND" == "start" || "$COMMAND" == "restart" ]]; then
                PROFILE="$1"
            elif [[ "$COMMAND" == "logs" || "$COMMAND" == "shell" ]]; then
                SERVICE_ARG="$1"
            fi
            shift
            ;;
    esac
done

# Default command
if [[ -z "$COMMAND" ]]; then
    COMMAND="start"
fi

# Main execution
main() {
    check_requirements
    setup_environment
    
    case "$COMMAND" in
        "start")
            check_ports
            start_services "$PROFILE"
            ;;
        "stop")
            stop_services
            ;;
        "restart")
            restart_services "$PROFILE"
            ;;
        "logs")
            show_logs "$SERVICE_ARG"
            ;;
        "shell")
            open_shell "$SERVICE_ARG"
            ;;
        "clean")
            clean_up
            ;;
        "status")
            show_status
            ;;
        "port-check")
            check_ports
            ;;
        "build")
            build_images
            ;;
        "test")
            run_tests
            ;;
        *)
            log_error "Unknown command: $COMMAND"
            show_help
            exit 1
            ;;
    esac
}

main "$@"