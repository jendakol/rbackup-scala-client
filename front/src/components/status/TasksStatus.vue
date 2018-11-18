<template>
    <v-container fluid>
        <v-hover>
            <v-layout row justify-space-around d-flex color="lighten-1" slot-scope="{ hover }" :class="`elevation-${hover ? 3 : 1}`">
                <v-list two-line>
                    <div v-for="(task, id) in runningTasks" v-bind:oncancel="this.cancel">
                        <FileUploadTask v-if="task.name === 'file-upload'" :id="id" :task="task" :cancel="cancel"></FileUploadTask>
                        <DirUploadTask v-if="task.name === 'dir-upload'" :id="id" :task="task" :cancel="cancel"></DirUploadTask>
                        <FileDownloadTask v-if="task.name === 'file-download'" :id="id" :task="task" :cancel="cancel"></FileDownloadTask>
                        <BackupSetUploadTask v-if="task.name === 'backupset-upload'" :id="id" :task="task"
                                             :cancel="cancel"></BackupSetUploadTask>
                    </div>
                </v-list>
            </v-layout>
        </v-hover>
    </v-container>
</template>

<script>
    import FileUploadTask from '../../components/status/tasks/FileUploadTask.vue';
    import DirUploadTask from '../../components/status/tasks/DirUploadTask.vue';
    import FileDownloadTask from '../../components/status/tasks/FileDownloadTask.vue';
    import BackupSetUploadTask from '../../components/status/tasks/BackupSetUploadTask.vue';

    export default {
        name: "TasksStatus",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function
        },
        components: {
            FileUploadTask,
            DirUploadTask,
            FileDownloadTask,
            BackupSetUploadTask
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
                console.log("Cancelling task "+id);
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