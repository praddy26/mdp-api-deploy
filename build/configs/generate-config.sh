#!/bin/sh
generate_project_config() {
    PROJECT_NAME=$1
    cat << EOF
{
    "stacks": [
        "$PROJECT_NAME"
    ],
    "templates": [
        "$PROJECT_NAME"
    ]
}
EOF
}

if [ "$1" = "" ] ; then
    echo "Please pass the project name argument."
    exit 1
fi

generate_project_config "$1"