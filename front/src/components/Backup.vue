<template>
    <v-tabs v-model="active" fill-height>
        <v-tab v-for="(backupSet, id) in backupSets" :key="id">
            {{backupSet.name}}
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
            this.ajax("backupSetsList")
                .then(response => {
                    if (response.success) {
                        this.backupSets = response.data;
                    } else {
                        this.$snotify.error("Could not load backup sets :-(")
                    }
                });
        },
        data() {
            return {
                active: 0,
                backupSets: [],
            }
        }, methods: {}
    }
</script>