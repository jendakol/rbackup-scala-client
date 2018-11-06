<template>
    <v-container fluid>
        <v-hover>
            <v-layout row justify-space-around d-flex color="lighten-1" slot-scope="{ hover }" :class="`elevation-${hover ? 3 : 1}`">
                <v-list two-line>
                    <v-list-tile v-for="(task, id) in runningTasks" :key="id">
                        <v-list-tile-avatar>
                            <v-icon>{{task.icon}}</v-icon>
                        </v-list-tile-avatar>
                        &nbsp;
                        <v-list-tile-content>
                            <v-list-tile-title v-html="task.name"></v-list-tile-title>
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
                </v-list>
            </v-layout>
        </v-hover>
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
            cancel(id) {
                this.asyncActionWithNotification("cancelTask", {id: id}, "Cancelling task", (resp) => new Promise((success, error) => {
                    if (resp.success) {
                        // TODO display cancelled task?
                        success("Task was successfully cancelled!");
                    } else {
                        error("Task was not cancelled")
                    }
                }));
            },
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