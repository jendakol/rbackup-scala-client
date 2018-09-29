<template>
    <div>
        <v-jstree :data="fileTreeData" :item-events="itemEvents" :async="loadData" show-checkbox multiple allow-batch
                  whole-row></v-jstree>

        <BottomBar>
            <v-btn @click="saveBackupSelection" color="success">Save</v-btn>
        </BottomBar>

        <vue-context ref="fileMenu">
            <ul>
                <li @click="uploadManually">Upload file now</li>
            </ul>
        </vue-context>
        <vue-context ref="dirMenu">
            <ul>
                <li @click="uploadManually">Upload dir now</li>
            </ul>
        </vue-context>
    </div>
</template>

<script>
    import VJstree from 'vue-jstree';
    import {VueContext} from 'vue-context';
    import JSPath from 'jspath';

    import BottomBar from '../components/BottomBar.vue';

    export default {
        name: "Backup",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function
        },
        components: {
            VJstree,
            VueContext,
            BottomBar
        },
        created() {
            this.registerWsListener(this.receiveWs)
        },
        data() {
            return {
                fileTreeData: [],
                loadData: (oriNode, resolve) => {
                    let path = oriNode.data.value;

                    this.ajax("dirList", {path: path != undefined ? path + "" : ""})
                        .then(response => {
                            resolve(response)
                        })

                },
                rightClicked: null,
                itemEvents: {
                    contextmenu: (a, item, event) => {
                        this.rightClicked = item;

                        if (item.isFile) {
                            this.$refs.fileMenu.open(event);
                        } else if (item.isDir) {
                            this.$refs.dirMenu.open(event);
                        } else console.log("It's weird - not version nor dir nor file");

                        event.preventDefault()
                    }
                }
            }
        }, methods: {
            saveBackupSelection(){
                alert()
            },
            uploadManually() {
                let path = this.rightClicked.value;

                this.asyncActionWithNotification("uploadManually", {path: path}, "Manually uploading " + path, (resp) => new Promise((success, error) => {
                    if (resp.success) {
                        success("Manual upload of " + path + " successful!")
                    } else {
                        error("Upload of " + path + " was NOT successful, because " + resp.reason)
                    }
                }));
            },
            receiveWs(message) {
                // switch (message.type) {
                //     case "fileUploaded": {
                //         let data = message.data;
                //         let node = this.selectTreeNode(data.path);
                //
                //         if (node != undefined) {
                //             node.children = data.versions;
                //             node.isLeaf = false;
                //         }
                //     }
                //         break;
                // }
            },
            selectTreeNode(path) {
                return JSPath.apply("..{.value === '" + path + "'}", this.fileTreeData)[0]
            },
        }
    }
</script>