<template>
    <div>
        BACKUP SET
        {{ this.backupSet }}
        {{ this.backupSetFiles }}
        <v-btn color="success" @click="runBackup">Run now</v-btn>
        <v-container fluid fill-height>

            <v-jstree :data="fileTreeData" :async="loadData" show-checkbox multiple allow-batch
                      whole-row></v-jstree>

        </v-container>

        <BottomBar>
            <v-btn @click="saveBackupSelection" color="success">Save</v-btn>
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
            },
            selectTreeNode(path) {
                return JSPath.apply("..{.value === '" + path + "'}", this.fileTreeData)[0]
            },
        }
    }
</script>