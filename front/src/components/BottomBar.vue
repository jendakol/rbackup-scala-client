<template>
    <b-navbar toggleable="md" type="dark" variant="dark" fixed="bottom">

        <b-navbar-toggle target="nav_collapse"></b-navbar-toggle>

        <b-collapse is-nav id="nav_collapse">
            <b-navbar-nav class="ml-auto" right>
                <b-button-toolbar>
                    <b-button-group class="mx-1">
                        <slot/>
                    </b-button-group>
                </b-button-toolbar>
            </b-navbar-nav>

        </b-collapse>
    </b-navbar>
</template>