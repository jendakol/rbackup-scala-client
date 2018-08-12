<template>
    <div>
        <div id="loginFormTabs">
            <b-tabs>
                <b-tab title="Log in" active>
                    <b-form @submit="doLogin">
                        <b-form-input id="username"
                                      type="text"
                                      v-model="loginForm.username"
                                      required
                                      placeholder="Username" class="formInput"/>
                        <b-form-input id="password"
                                      type="password"
                                      v-model="loginForm.password"
                                      required
                                      placeholder="Password" class="formInput"/>
                        <b-button type="submit" variant="primary" class="formButton">Login</b-button>
                    </b-form>
                </b-tab>
                <b-tab title="Sign up">
                    <br>I'm the second tab content
                </b-tab>
            </b-tabs>
        </div>
    </div>
</template>

<script>
    const HostUrl = "localhost:9000";

    import axios from 'axios';

    export default {
        name: 'LoginForm',
        data() {
            return {
                loginForm: {
                    username: null,
                    password: null
                }
            }
        },
        props: {
            ajax: Function,
            asyncActionWithNotification: Function
        },
        methods: {
            doLogin(evt) {
                evt.preventDefault();

                this.asyncActionWithNotification("login", {
                        username: this.loginForm.username,
                        password: this.loginForm.password
                    }, "Logging in", (resp) => new Promise((success, error) => {
                        if (resp.success) {
                            success("Login successful!");
                            this.$emit("login")
                        } else {
                            error("Login unsuccessful")
                        }
                    })
                );

            },
            register() {
                this.ajax("register", {username: "bb22", password: "ahoj"}).then(t => {
                    if (t.success) {
                        this.cloudResponseMessage = "Account registered: " + t.account_id
                    } else {
                        this.cloudResponseMessage = "Account NOT registered, because: " + t.reason
                    }
                })
            },
        }
    }
</script>

<style scoped lang="scss">

    div#loginFormTabs {
        border: 1px solid black;
        border-radius: 5px;
        display: block;
        padding: 5px;
        width: 500px;
        height: 200px;
        position: absolute;
        left: 50%;
        top: 50%;
        margin: -100px 0 0 -250px;
    }

    .formInput {
        margin: 10px 0 0 0;
    }

    .formButton {
        margin: 10px 0 0 -75px;
        display: block;
        width: 150px;
        position: relative;
        left: 50%;
    }
</style>