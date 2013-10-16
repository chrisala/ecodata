<%@ page import="org.apache.commons.lang.StringEscapeUtils" %>
<!doctype html>
<html>
<head>
    <meta name="layout" content="adminLayout"/>
    <title>Activity model - Admin - Data capture - Atlas of Living Australia</title>
    <r:require modules="knockout,jquery_ui,knockout_sortable"/>
</head>

<body>
<content tag="pageTitle">Activity model</content>
<content tag="adminButtonBar">
    <button type="button" data-bind="click:save" class="btn btn-success">Save</button>
    <button type="button" data-bind="click:revert" class="btn">Cancel</button>
</content>
<div class="row-fluid clearfix">
    <div class="span5">
        <h2>Activities</h2>
        <ul data-bind="sortable:{data:activities}" class="sortableList">
            <li class="item">
                <div data-bind="click:toggle"><span data-bind="text:name"></span></div>
                <div data-bind="visible:expanded" class="details clearfix" style="display:none;">
                    <div data-bind="template: {name: displayMode}"></div>
                </div>
            </li>
        </ul>
        <span data-bind="click:addActivity" class="clickable"><i class="icon-plus"></i> Add new</span>
    </div>
    <div class="span5 pull-right">
        <h2>Outputs</h2>
        <ul data-bind="sortable:outputs" class="sortableList">
            <li data-bind="css:{referenced: isReferenced}" class="item">
                <div data-bind="click:toggle">
                    <span data-bind="click:addToCurrentActivity, clickBubble:false, visible:isAddable" class="add-arrow clickable" title="Add output to current activity"><i class="icon-arrow-left"></i></span>
                    <span data-bind="text:name"></span>
                </div>
                <div data-bind="visible:expanded" class="details clearfix" style="display:none;">
                    <div data-bind="template: {name: displayMode}"></div>
                </div>
            </li>
        </ul>
        <span data-bind="click:addOutput" class="clickable"><i class="icon-plus"></i> Add new</span>
    </div>
</div>

<script id="viewActivityTmpl" type="text/html">
    <div>Type: <span data-bind="text:type"></span></div>
    <div>Outputs: <ul data-bind="foreach:outputs">
        <li data-bind="text:$data"></li>
    </ul></div>
    <button data-bind="click:$root.removeActivity" type="button" class="btn btn-mini pull-right">Remove</button>
    <button data-bind="click:edit" type="button" class="btn btn-mini pull-right">Edit</button>
</script>

<script id="editActivityTmpl" type="text/html">
    <div style="margin-top:4px"><span class="span2">Name:</span> <input type="text" class="input-large pull-right" data-bind="value:name"></div>
    <div class="clearfix"><span class="span2">Type:</span> <select data-bind="options:['Activity','Assessment'],value:type" class="pull-right"></select></div>
    <div>Outputs: <ul data-bind="sortable:{data:outputs}" class="output-drop-target sortableList small">
        <li>
            <span data-bind="text:$data"></span>
            <span class="pull-right"><i data-bind="click:$parent.removeOutput" class="icon-remove"></i></span>
        </li>
    </ul></div>
    <button data-bind="click:done" type="button" class="btn btn-mini pull-right">Done</button>
</script>

<script id="viewOutputTmpl" type="text/html">
    <div>Template: <span data-bind="text:template"></span></div>
    <div>Score names: <ul data-bind="foreach:scores">
        <li><span data-bind="text:label"></span>, <span data-bind="text:aggregationType"></span></li>
    </ul></div>
    <button data-bind="click:$root.removeOutput" type="button" class="btn btn-mini pull-right">Remove</button>
    <button data-bind="click:edit" type="button" class="btn btn-mini pull-right">Edit</button>
</script>

<script id="editOutputTmpl" type="text/html">
    <div style="margin-top:4px"><span class="span3">Name:</span> <input type="text" class="input pull-right" data-bind="value:name"></div>
    <div class="clearfix"><span class="span3">Template:</span> <input type="text" class="input pull-right" data-bind="value:template"></div>
    <div>Scores: <ul data-bind="sortable:{data:scores}" class="sortableList small">
        <li>
            <div style="text-align:left;">
            Name: <input type="text" data-bind="value:name"/>
            </div>
            <div style="text-align:left;">
            Label: <input type="text" data-bind="value:label"/>
            </div>
            <div style="text-align:left;">
            Units: <input type="text" data-bind="value:units"/>
            </div>
            <div style="text-align:left;">
            Aggregation:
            <select data-bind="value:aggregationType">
                <option value="SUM">summed</option>
                <option value="COUNT">counted</option>
                <option value="AVERAGE">averaged</option>
                <option value="HISTOGRAM">count by value</option>
                <option value="SET">list of distinct values</option>
            </select>
            </div>
        </li>
    </ul><span data-bind="click:addScore" class="clickable"><i class="icon-plus"></i> Add new</span>
    </div>
    <button data-bind="click:done" type="button" class="btn btn-mini pull-right">Done</button>
</script>

<div class="expandable-debug clearfix">
    <hr />
    <h3>Debug</h3>
    <div>
        <h4>KO model</h4>
        <pre data-bind="text:ko.toJSON($root,null,2)"></pre>
        <h4>Input model</h4>
        <pre>${activitiesModel}</pre>
        <h4>Activities</h4>
        <pre data-bind="text:ko.toJSON(activities,null,2)"></pre>
        <h4>Outputs</h4>
        <pre data-bind="text:ko.toJSON(outputs,null,2)"></pre>
    </div>
</div>

<r:script>
    $(function(){

        var ActivityModel = function (act, model) {
            var self = this;
            this.name = ko.observable(act.name);
            this.type = ko.observable(act.type);
            this.outputs = ko.observableArray(act.outputs || []);
            this.expanded = ko.observable(false);
            this.toggle = function (data, event) {
                if (!self.expanded()) {
                    $.each(viewModel.activities(), function (i, obj) {
                        obj.expanded(false); // close all
                        obj.done(); // exit editing mode
                    });
                    self.expanded(true);
                    model.selectedActivity(self);
                } else {
                    self.expanded(false);
                    self.done(); // in case we were editing
                    model.selectedActivity(undefined);
                }
            };
            this.editing = ko.observable(false);
            this.edit = function () { self.editing(true) };
            this.done = function () { self.editing(false) };
            this.displayMode = function () {
                return self.editing() ? 'editActivityTmpl' : 'viewActivityTmpl';
            };
            this.removeOutput = function (data) {
                self.outputs.remove(data);
            };
            this.toJSON = function() {
                var js = ko.toJS(this);
                delete js.expanded;
                delete js.editing;
                return js;
            }
        };

        var ScoreModel = function (score) {
            var self = this;
            self.name = ko.observable(score.name);
            self.label = ko.observable(score.label)
            self.units = ko.observable(score.units)
            self.aggregationType = ko.observable(score.aggregationType)

        };

        var OutputModel = function (out, model) {
            var self = this;
            this.name = ko.observable(out.name);
            this.template = ko.observable(out.template);
            this.scores = ko.observableArray($.map(out.scores || [], function (obj,i) {
                return new ScoreModel(obj);
            }));
            this.scoreNames = ko.observableArray(out.scoreNames);

            // TODO - this is a temporary measure to convert score names into scores
            // Should be removed (along with the score names) when the conversion is complete.
            if (out.scoreNames) {
                $.each(out.scoreNames, function(i, name) {
                    var matchedScore = null;
                    $.each(self.scores(), function(j, score) {
                        if (score.name === name) {
                            matchedScore = score;
                            return false;
                        }
                    });
                    if (!matchedScore) {
                        self.scores.push(new ScoreModel({name:name, units:'', label:name, aggregationType:'SET'}))
                    }
                });
            }

            this.expanded = ko.observable(false);
            this.toggle = function (data, event) {
                if (!self.expanded()) {
                    $.each(viewModel.outputs(), function (i, obj) {
                        obj.expanded(false); // close all
                        obj.done(); // exit editing mode
                    });
                    self.expanded(true);
                } else {
                    self.expanded(false);
                    self.done(); // in case we were editing
                }
            };
            this.editing = ko.observable(false);
            this.edit = function () { self.editing(true) };
            this.done = function () { self.editing(false) };
            this.displayMode = function () {
                return self.editing() ? 'editOutputTmpl' : 'viewOutputTmpl';
            };
            this.addScore = function () {
                self.scores.push(new ScoreModel('new score'));
            };
            this.removeScore = function (data) {
                self.scores.remove(data);
            };
            this.isReferenced = ko.computed(function () {
                var current = model.selectedActivity(),
                    referenced = false;
                if (current === undefined) { return false; }
                $.each(current.outputs(), function (k, out) {
                    if (out === self.name()) {
                        referenced = true;
                    }
                });
                return referenced;
            });
            this.isAddable = ko.computed(function () {
                if (model.selectedActivity() === undefined) {
                    return false;
                }
                return !self.isReferenced();
            });
            this.addToCurrentActivity = function (data) {
                model.selectedActivity().outputs.push(data.name());
            };
            this.toJSON = function() {
                var js = ko.toJS(this);
                delete js.expanded;
                delete js.editing;
                delete js.isReferenced;
                delete js.isAddable;

                return js;
            }
        };

        var ViewModel = function (model) {
            var self = this;
            this.activities = ko.observableArray($.map(model.activities, function (obj, i) {
                return new ActivityModel(obj, self);
            }));
            this.selectedActivity = ko.observable();
            this.outputs = ko.observableArray($.map(model.outputs, function (obj, i) {
                return new OutputModel(obj, self);
            }));
            this.addActivity = function () {
                var act = new ActivityModel({name: 'new activity', type: 'Activity'}, self);
                self.activities.push(act);
                act.expanded(true);
                act.editing(true);
            };
            this.removeActivity = function () {
                self.activities.remove(this);
            };
            this.addOutput = function () {
                var out = new OutputModel({name: 'new output'}, self);
                self.outputs.push(out);
                out.expanded(true);
                out.editing(true);
            };
            this.removeOutput = function () {
                self.outputs.remove(this);
            };
            this.revert = function () {
                document.location.reload();
            };
            this.save = function () {
                var model = ko.toJS(self);
                $.ajax("${createLink(action: 'updateActivitiesModel')}", {
                    type: 'POST',
                    data: vkbeautify.json(model,2),
                    contentType: 'application/json',
                    success: function (data) {
                        if (data !== 'error') {
                            document.location.reload();
                        } else {
                            alert(data);
                        }
                    },
                    error: function () {
                        alert('failed');
                    },
                    dataType: 'text'
                });
            };
        };

        var viewModel = new ViewModel(${activitiesModel});
        ko.applyBindings(viewModel);
    });
</r:script>
</body>
</html>