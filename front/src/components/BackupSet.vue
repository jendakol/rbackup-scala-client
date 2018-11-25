<template>
    <div>
        <div>{{this.backupSet.name}}</div>
        <div>{{ this.backupSetFiles }}</div>

        <div>Last executed: {{this.backupSet.last_execution}}</div>
        <div>Next execution: {{this.backupSet.next_execution}}</div>


        <v-btn v-if="this.backupSet.processing === false" color="success" @click="runBackup">Run now</v-btn>
        <div v-else>
            Backup running
        </div>

        <v-container fluid fill-height>

            <v-jstree :data="fileTreeData" :async="loadData" show-checkbox multiple allow-batch
                      whole-row></v-jstree>

        </v-container>

        <BottomBar>
            <v-btn v-if="this.backupSet.processing === false" @click="saveBackupSelection" color="success">Save</v-btn>
            <v-btn v-else @click="saveBackupSelection" color="success" disabled>Save</v-btn>
        </BottomBar>
    </div>
</template>

<script>
    import VJstree from 'vue-jstree';
    import JSPath from 'jspath';

    import BottomBar from '../components/BottomBar.vue';

    export default {
        name: "BackupSet",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function,
            backupSet: Object
        },
        components: {
            VJstree,
            BottomBar
        },
        data() {
            return {
                fileTreeData: [],
                backupSetFiles: [],
                loadData: (oriNode, resolve) => {
                    let path = oriNode.data.value;

                    this.ajax("dirList", {path: path != undefined ? path + "" : ""})
                        .then(response => {
                            resolve(response)
                        })

                },
            }
        },
        created() {
            this.registerWsListener(this.receiveWs);

            this.ajax("backupSetDetails", {id: this.backupSet.id})
                .then(response => {
                    if (response.success) {
                        this.backupSetFiles = response.data.files;
                    } else {
                        this.$snotify.error("Could not load backup sets :-(")
                    }
                })
        },
        methods: {
            runBackup() {
                this.asyncActionWithNotification("backupSetExecute", {id: this.backupSet.id}, "Running backup", (resp) => new Promise((success, error) => {
                        if (resp.success) {
                            success("Backup started!");
                        } else {
                            error("Could not run backup")
                        }
                    })
                );
            },
            saveBackupSelection() {
                this.ajax("backupSetFiles", {id: this.backupSet.id, files: this.fileTreeData})
            },
            receiveWs(message) {
                switch (message.type) {
                    case "backupSetUpdate": {
                        if (message.data.id === this.backupSet.id) {
                            this.backupSet = message.data
                        }
                    }
                        break;
                    case "backupSetDetailsUpdate": {
                        if (message.data.id === this.backupSet.id) {
                            switch (message.data.type) {
                                case "files" : {
                                    this.backupSetFiles = message.data.files
                                }
                                    break;
                                case "processing" : {
                                    this.backupSet.processing = message.data.processing;
                                    this.backupSet.last_execution = message.data.last_execution;
                                    this.backupSet.next_execution = message.data.next_execution;
                                }
                                    break;
                            }
                        }
                    }
                        break;
                }
            },
            selectTreeNode(path) {
                return JSPath.apply("..{.value === '" + path + "'}", this.fileTreeData)[0]
            },
        }
    }
</script>