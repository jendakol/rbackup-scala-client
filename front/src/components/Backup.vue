<template>
    <div>
        <v-jstree :data="fileTreeData" :item-events="itemEvents" :async="loadData" show-checkbox multiple allow-batch
                  whole-row></v-jstree>

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

    export default {
        name: "Backup",
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
        }
    }
</script>