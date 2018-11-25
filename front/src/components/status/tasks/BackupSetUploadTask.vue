<template>
    <v-list-tile>
        <v-list-tile-avatar>
            <v-icon>list</v-icon>
        </v-list-tile-avatar>
        <v-list-tile-content>
            <v-list-tile-title>Backup set upload</v-list-tile-title>
            <v-list-tile-sub-title v-html="task.data.name"></v-list-tile-sub-title>
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
        name: "BackupSetUploadTask",
        props: {
            id: String,
            task: Object,
            cancel: Function
        },
        data() {
            return {}
        },
    }
</script>