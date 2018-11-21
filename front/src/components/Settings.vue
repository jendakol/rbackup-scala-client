<template>
    <div>
        <!--SETTINGS-->
        <!--{{this.settings}}-->

        <v-item-group multiple v-for="(settings, name) in this.settings" :key="name">
            <v-subheader>{{name}}</v-subheader>
            <v-item v-for="(setting, name) in settings" :key="name">
                <v-switch v-if="setting.type==='boolean'"
                          :label="name"
                          v-model="setting.value"
                          @change="updateSettings"
                ></v-switch>

                <v-container v-if="setting.type==='number'" fluid>
                    <v-flex
                            shrink
                            style="width: 60px">
                        <v-text-field
                                v-model="setting.value"
                                class="mt-0"
                                single-line
                                type="number"
                                @input="updateSettings">
                        </v-text-field>
                        <span>{{name}}</span>
                    </v-flex>
                </v-container>
            </v-item>
        </v-item-group>

    </div>
</template>

<script>
    import VJstree from 'vue-jstree';
    import {VueContext} from 'vue-context';
    import JSPath from 'jspath';

    export default {
        name: "Settings",
        props: {
            ajax: Function,
            asyncActionWithNotification: Function,
            registerWsListener: Function
        },
        components: {},
        created() {
            this.registerWsListener(this.receiveWs);

            this.ajax("settingsLoad").then(response => {
                if (response.success) {
                    let newSettings = {};

                    let respData = response.data;

                    Object.keys(respData).forEach(function (key, index) {
                        let newSetting = {};
                        let settingsSection = respData[key];

                        Object.keys(settingsSection).forEach(function (key, index) {
                            newSetting[key] = {value: eval(settingsSection[key].value), type: settingsSection[key].type}
                        });

                        newSettings[key] = newSetting
                    });

                    this.settings = newSettings;
                } else {
                    this.$snotify.error("Could not load settings :-(")
                }
            })
        },
        data() {
            return {
                settings: null
            }
        },
        methods: {
            receiveWs(message) {
            },
            updateSettings() {
                let updatedSettings = {};
                let setts = this.settings;

                Object.keys(setts).forEach(function (key, index) {
                    let sett = setts[key];
                    Object.keys(sett).forEach(function (key, index) {
                        updatedSettings[key] = sett[key].value + ""
                    });
                });

                this.ajax("settingsSave", {settings: updatedSettings}).then(response => {
                    if (!response.success) {
                        this.$snotify.error("Could not save settings :-(")
                    }
                })
            }

        }
    }
</script>