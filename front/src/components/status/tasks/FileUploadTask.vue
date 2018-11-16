<template>
    <v-list-tile :key="id">
        <v-list-tile-avatar>
            <v-icon>insert_drive_file</v-icon>
        </v-list-tile-avatar>
        <v-list-tile-content>
            <v-list-tile-title>File upload</v-list-tile-title>
            <v-list-tile-sub-title v-html="task.data.file_name"></v-list-tile-sub-title>
        </v-list-tile-content>
        <v-progress-circular indeterminate small></v-progress-circular>
        <v-list-tile-action align-right>
            <v-tooltip bottom>
                <v-icon large slot="activator" color="red lighten-1" @click="cancel(id)">clear</v-icon>
                <span>Cancel this task</span>
            </v-tooltip>
        </v-list-tile-action>
    </v-list-tile>
</template>

<script>
    export default {
        name: "FileUploadTask",
        props: {
            task: Object,
            id: String
        },
        data() {
            return {}
        },
    }
</script>