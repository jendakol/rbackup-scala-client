<template>
    <v-container fluid>
        <v-layout>
            <v-flex>
                <v-card>
                    <v-container fluid grid-list-md>
                        <v-layout row wrap>
                            <v-flex xs12>
                                <v-card>
                                    <v-toolbar dense color="primary">
                                        <v-toolbar-title class="white--text">Connection</v-toolbar-title>
                                    </v-toolbar>
                                    <ConnectionStatus :ajax="this.ajax" :registerWsListener="this.registerWsListener"
                                                      :asyncActionWithNotification="this.asyncActionWithNotification"/>
                                </v-card>
                            </v-flex>
                            <v-flex xs12>
                                <v-card>
                                    <v-toolbar dense color="primary">
                                        <v-toolbar-title class="white--text">Transfers status</v-toolbar-title>
                                    </v-toolbar>
                                    <TransfersStatus :ajax="this.ajax" :registerWsListener="this.registerWsListener"
                                                     :asyncActionWithNotification="this.asyncActionWithNotification"/>
                                </v-card>
                            </v-flex>
                            <v-flex xs12>
                                <v-card>
                                    <v-toolbar dense color="primary">
                                        <v-toolbar-title class="white--text">Running tasks</v-toolbar-title>
                                    </v-toolbar>
                                    <TasksStatus :ajax="this.ajax" :registerWsListener="this.registerWsListener"
                                                 :asyncActionWithNotification="this.asyncActionWithNotification"/>
                                </v-card>
                            </v-flex>
                        </v-layout>
                    </v-container>
                </v-card>
            </v-flex>
        </v-layout>
    </v-container>
</template>

<script>
    import ConnectionStatus from '../components/status/ConnectionStatus.vue';
    import TransfersStatus from './status/TransfersStatus.vue';
    import TasksStatus from '../components/status/TasksStatus.vue';

    export default {
        name: "Status",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function
        },
        components: {
            ConnectionStatus,
            TransfersStatus,
            TasksStatus,
        },
        data() {
            return {}
        },
        created() {
        },
        methods: {}
    }
</script>