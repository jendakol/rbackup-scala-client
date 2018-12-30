<template>
    <v-tabs v-model="active" fill-height>
        <v-dialog v-model="addDialog" width="500">
            <!--<v-icon slot="activator" top medium>add_box</v-icon>-->
            <v-btn slot="activator" width="50" color="success">
                <v-icon top medium>add_box</v-icon>
            </v-btn>

            <v-card>
                <v-card-title class="headline grey lighten-2" primary-title>
                    Add new backup set
                </v-card-title>

                <v-card-text>
                    <v-text-field label="Name" v-model="newName" v-on:keyup.enter="addNewBackupSet"></v-text-field>
                </v-card-text>

                <v-divider></v-divider>

                <v-card-actions>
                    <v-spacer></v-spacer>
                    <v-btn
                            color="success"
                            flat
                            @click="addNewBackupSet">
                        Create
                    </v-btn>
                </v-card-actions>
            </v-card>
        </v-dialog>

        <v-tab v-for="(backupSet, id) in backupSets" :key="id">
            {{backupSet.name}} &nbsp;
            <v-icon top medium @click="removeBackupSet(backupSet.id)">cancel</v-icon>
        </v-tab>
        <v-tab-item v-for="(backupSet, id) in backupSets" :key="id"
                    v-bind:ajax="this.ajax"
                    v-bind:registerWsListener="this.registerWsListener"
                    v-bind:asyncActionWithNotification="this.asyncActionWithNotification">
            <v-container fluid fill-height>
                <v-flex grow>
                    <BackupSet :key="id" :backupSet="backupSet" :ajax="ajax"
                               :registerWsListener="registerWsListener"
                               :asyncActionWithNotification="asyncActionWithNotification"/>
                </v-flex>
            </v-container>
        </v-tab-item>
    </v-tabs>
</template>

<script>
    import BackupSet from '../components/BackupSet.vue';

    export default {
        name: "Backup",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function
        },
        components: {
            BackupSet
        },
        created() {
            if (!this.initialized)
                this.refreshBackupSets()
        },
        data() {
            return {
                active: 0,
                addDialog: 0,
                deleteDialog: 0,
                backupSets: [],
                newName: "",
                initialized: false
            }
        }, methods: {
            refreshBackupSets() {
                this.ajax("backupSetsList")
                    .then(response => {
                        if (response.success) {
                            this.backupSets = response.data;
                            this.initialized = true;
                        } else {
                            this.$snotify.error("Could not load backup sets :-(")
                        }
                    });
            },
            addNewBackupSet() {
                if (this.newName.trim() !== "") {
                    this.addDialog = false;

                    this.asyncActionWithNotification("backupSetNew", {
                            name: this.newName
                        }, "Creating backup set", (resp) => new Promise((success, error) => {
                            if (resp.success === true) {
                                success("Backup set created!");
                                this.refreshBackupSets()
                            } else if (resp.success === false) {
                                error("Backup set could not be created")
                            } else {
                                error("Backup set could not be created: " + resp.error)
                            }
                        })
                    );
                }
            },
            removeBackupSet(id) {
                this.deleteDialog = false;

                this.$confirm('Do you really want to delete backup set?', {title: 'Warning'}).then(res => {
                    if (res) {
                        this.asyncActionWithNotification("backupSetDelete", {
                                id: id
                            }, "Deleting backup set", (resp) => new Promise((success, error) => {
                                if (resp.success === true) {
                                    success("Backup set deleted!");
                                    this.refreshBackupSets()
                                } else if (resp.success === false) {
                                    error("Backup set could not be deleted")
                                } else {
                                    error("Backup set could not be deleted: " + resp.error)
                                }
                            })
                        );
                    }
                });
            }
        }
    }
</script>