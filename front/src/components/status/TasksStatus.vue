<template>
    <v-container fluid>
        <v-layout row>
            <v-list two-line>
                <v-list-tile v-for="task in runningTasks">
                    <v-progress-circular indeterminate></v-progress-circular>
                    &nbsp;
                    <v-list-tile-content>
                        <v-list-tile-title v-html="task.name"></v-list-tile-title>
                        <v-list-tile-sub-title v-html="task.data.file_name"></v-list-tile-sub-title>
                    </v-list-tile-content>
                </v-list-tile>
            </v-list>
        </v-layout>
    </v-container>
</template>

<script>
    export default {
        name: "TasksStatus",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function
        },
        data() {
            return {
                runningTasks: []
            }
        },
        created() {
            this.registerWsListener(this.receiveWs)
        },
        methods: {
            receiveWs(message) {
                switch (message.type) {
                    case "runningTasks": {
                        this.runningTasks = message.data;
                    }
                        break;
                }
            },
        }
    }
</script>