<template v-else>
    <v-app>
        <div>
            <div v-if="clientStatus === 'INSTALLED'">
                <LoginForm v-on:login="updateStatus" :ajax="this.ajax" :asyncActionWithNotification="this.asyncActionWithNotification"/>
            </div>
            <div v-if="clientStatus === 'DISCONNECTED'">
                Disconnected
            </div>
            <div v-if="clientStatus === 'INITIALIZING'">
                App initializing
            </div>
            <div v-if="clientStatus === 'READY'">
                <MainApp v-on:logout="updateStatus" :hostUrl="this.hostUrl" :ajax="this.ajax"
                         :asyncActionWithNotification="this.asyncActionWithNotification"/>
            </div>
        </div>

        <vue-snotify/>
    </v-app>

</template>

<script>
    import axios from 'axios';

    import LoginForm from '../components/LoginForm.vue';
    import MainApp from '../components/MainApp.vue';

    export default {
        components: {
            MainApp,
            LoginForm
        },
        data() {
            return {
                hostUrl: "localhost:3370",
                clientStatus: null,
            }
        },
        created: function () {
            setTimeout(() => {
                this.updateStatus(); // TODO timeout?!
            }, 100);
        },
        methods: {
            ajax(name, data) {
                return axios.post("http://" + this.hostUrl + "/ajax-api", {name: name, data: data}, {timeout: 5000})
                    .then(t => {
                        return t.data;
                    }).catch(err => {
                        console.log(err);
                        this.clientStatus = "DISCONNECTED"
                    })
            }, asyncActionWithNotification(actionName, data, initialText, responseToPromise) {
                this.$snotify.async(initialText, () => new Promise((resolve, reject) => {
                    this.ajax(actionName, data).then(resp => {
                        responseToPromise(resp)
                            .then(text => {
                                resolve({
                                    body: text,
                                    config: {
                                        closeOnClick: true,
                                        timeout: 3500
                                    }
                                })
                            }, errText => reject({
                                body: errText,
                                config: {
                                    // TODO HTML formatting
                                    // html: '<div class="snotifyToast__title"><b>Html Bold Title</b></div><div class="snotifyToast__body"><i>Html</i> <b>toast</b> <u>content</u></div>',
                                    closeOnClick: true,
                                    timeout: 3500
                                }
                            }))
                    }).catch(err => {
                        console.log(JSON.stringify(err.response.data));
                        responseToPromise(err.response.data)
                            .then(text => {
                                resolve({
                                    body: text,
                                    config: {
                                        closeOnClick: true,
                                        timeout: 3500
                                    }
                                })
                            }, errText => reject({
                                body: errText,
                                config: {
                                    // TODO HTML formatting
                                    // html: '<div class="snotifyToast__title"><b>Html Bold Title</b></div><div class="snotifyToast__body"><i>Html</i> <b>toast</b> <u>content</u></div>',
                                    closeOnClick: true,
                                    timeout: 3500
                                }
                            }))
                    })
                }));
            },
            updateStatus() {
                this.ajax("status").then(resp => {
                    if (resp.success) {
                        this.clientStatus = resp.status;

                        // plan refresh if it went wrong
                        if (resp.status === "INITIALIZING" || resp.status === "DISCONNECTED") {
                            setTimeout(() => this.updateStatus(), 1000)
                        }
                    } else {
                        this.$snotify.error(resp.message)
                    }
                })
            }
        },
    }
</script>

<style scoped lang="scss">
</style>
