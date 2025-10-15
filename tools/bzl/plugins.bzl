CORE_PLUGINS = [
    "codemirror-editor",
    "commit-message-length-validator",
    "delete-project",
    "download-commands",
    "gitiles",
    "hooks",
    "plugin-manager",
    "replication",
    "reviewnotes",
    "singleusergroup",
    "webhooks",
]

CUSTOM_PLUGINS = [
    # Add custom core plugins here
    "lfs",
    "its-base",
    "its-jira",
]

CUSTOM_PLUGINS_TEST_DEPS = [
    # Add custom core plugins with tests deps here
    "its-jira",
]
