<template>
    <div>
        <v-btn v-if="this.backupSet.processing === false" @click="runBackup"
               absolute
               dark
               fab
               top
               right
               color="success">
            <v-icon>backup</v-icon>
        </v-btn>
        <div v-else>
            <loading :active="true"
                     :can-cancel="false"
                     :is-full-page="false">
                <div slot="after" class="backupRunning">Backup running</div>
            </loading>
        </div>

        <div>Last executed: {{this.backupSet.last_execution}}</div>
        <div>Next execution: {{this.backupSet.next_execution}}</div>

        <v-container fluid fill-height>

            <v-jstree :data="fileTreeData" :async="loadData" @item-click="this.updateSelectedFiles" show-checkbox multiple allow-batch
                      whole-row></v-jstree>

        </v-container>
    </div>
</template>

<script>
    import VJstree from 'vue-jstree';
    import JSPath from 'jspath';
    import Loading from 'vue-loading-overlay';
    import 'vue-loading-overlay/dist/vue-loading.css';

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
            Loading
        },
        data() {
            return {
                fileTreeData: [],
                backupSetFiles: [],
                treeLoaded: false,
                filesLoaded: false,
                loadingShow: true,
                loadData: (oriNode, resolve) => {
                    let path = oriNode.data.value;

                    this.ajax("dirList", {path: path != undefined ? path + "" : ""})
                        .then(response => {
                            resolve(response);
                            this.treeLoaded = true;
                            this.selectFilesInTree();
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
                        this.filesLoaded = true;
                    } else {
                        this.$snotify.error("Could not load backup sets :-(")
                    }
                });
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
                this.ajax("backupSetFiles", {id: this.backupSet.id, paths: this.backupSetFiles})
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
            findTreeNode(path) {
                return JSPath.apply('..{.value === "' + path.replace(/\\/g,"\\\\") + '"}', this.fileTreeData)[0]
            },
            selectFilesInTree() {
                if (!this.filesLoaded || !this.treeLoaded) {
                    setTimeout(this.selectFilesInTree, 200);
                    return;
                }

                this.backupSetFiles.forEach((filePath) => {
                    let node = this.findTreeNode(filePath);
                    if (node !== undefined && node.loading === false) {
                        node.selected = true;
                        this.selectChildren(node, true)
                    }
                })
            },
            selectChildren(node, select) {
                if (node.children !== undefined) {
                    node.children.forEach(children => {
                        if (children.loading === false) this.selectNode(children, select);
                    })
                }
            },
            updateSelectedFiles(n, fileNode, e) {
                if (fileNode.selected) {
                    this.addFileToSelected(fileNode.value);
                } else {
                    let parent = this.getParentNode(fileNode);

                    if (parent !== undefined && parent.selected) {
                        this.selectChildren(parent, true);
                        this.selectNode(parent, false);

                        fileNode.selected = false;
                    }

                    this.selectChildren(fileNode, false);
                    this.selectNode(fileNode, false)
                }

                this.saveBackupSelection();
            },
            selectNode(node, select) {
                node.selected = select;
                let filePath = node.value;

                if (select) {
                    this.addFileToSelected(filePath);
                    this.selectChildren(node, true);
                } else {
                    this.removeFileFromSelected(filePath);
                }
            },
            addFileToSelected(filePath) {
                if (this.backupSetFiles.indexOf(filePath) < 0) {
                    this.backupSetFiles.push(filePath);
                }
            },
            removeFileFromSelected: function (filePath) {
                let pos;
                while ((pos = this.backupSetFiles.indexOf(filePath)) >= 0) {
                    this.backupSetFiles.splice(pos, 1);
                }
            },
            getParentNode(node) {
                let filePathParts = node.value.split("/");
                filePathParts.pop();

                if (filePathParts.length === 1 && filePathParts[0] === "") return this.findTreeNode("/");

                return this.findTreeNode(filePathParts.join("/"))
            },
        }
    }
</script>

<style scoped lang="scss">
    .backupRunning {
        display: block;
        padding: 10px;
        width: 200px;
        text-align: center;
        position: relative;
        left: -68px;
        font-size: larger;
    }
</style>