<template>
    <div>

        <v-jstree :data="fileTreeData" :item-events="itemEvents" :async="loadData" show-checkbox multiple allow-batch
                  whole-row></v-jstree>

        <vue-context ref="versionMenu">
            <ul>
                <li @click="restore">Restore this version</li>
            </ul>
        </vue-context>
        <vue-context ref="fileMenu">
            <ul>
                <li @click="alert('???')">Restore to last version</li>
            </ul>
        </vue-context>
        <vue-context ref="dirMenu">
            <ul>
                <li @click="alert('???')">Restore this directory</li>
            </ul>
        </vue-context>
    </div>
</template>

<script>
    import VJstree from 'vue-jstree';
    import {VueContext} from 'vue-context';

    export default {
        name: "Restore",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function
        },
        components: {
            VJstree,
            VueContext
        },
        created() {
        },
        data() {
            return {
                fileTreeData: [],
                loadData: (oriNode, resolve) => {
                    let path = oriNode.data.value;

                    // axios.post('http://localhost:9000/ajax-api', {name: "dirList", data: {path: path != undefined ? path + "" : ""}})
                    this.ajax("dirList", {path: path != undefined ? path + "" : ""})
                        .then(response => {
                            resolve(response)
                        })

                },
                rightClicked: null,
                itemEvents: {
                    contextmenu: (a, item, event) => {
                        this.rightClicked = item;

                        if (item.isVersion) {
                            this.$refs.versionMenu.open(event);
                        } else if (item.isFile) {
                            this.$refs.fileMenu.open(event);
                        } else if (item.isDir) {
                            this.$refs.dirMenu.open(event);
                        } else console.log("It's weird - not version nor dir nor file");

                        event.preventDefault()
                    }
                }
            }
        },
        methods: {
            restore() {
                let path = this.rightClicked.path;
                let versionId = this.rightClicked.versionId;
                let versionDateTime = this.rightClicked.text;

                this.asyncActionWithNotification("download", {
                    path: path,
                    version_id: versionId
                }, "Restoring " + path + " to " + versionDateTime, (resp) => new Promise((success, error) => {
                    if (resp.success) {
                        success("File " + path + " successfully restored to " + versionDateTime + "!")
                    } else {
                        error(resp.message)
                    }
                }));
            },
        }
    }
</script>