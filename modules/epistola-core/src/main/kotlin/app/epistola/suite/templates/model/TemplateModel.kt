@file:Suppress("ktlint:standard:filename")

package app.epistola.suite.templates.model

// V2 types (node/slot graph model)
typealias TemplateDocument = app.epistola.template.model.TemplateDocument
typealias Node = app.epistola.template.model.Node
typealias Slot = app.epistola.template.model.Slot
typealias ThemeRef = app.epistola.template.model.ThemeRef
typealias ThemeRefInherit = app.epistola.template.model.ThemeRefInherit
typealias ThemeRefOverride = app.epistola.template.model.ThemeRefOverride

// Shared types (used by both v1 and v2)
typealias PageSettings = app.epistola.template.model.PageSettings
typealias PageFormat = app.epistola.template.model.PageFormat
typealias Orientation = app.epistola.template.model.Orientation
typealias Margins = app.epistola.template.model.Margins
typealias DocumentStyles = app.epistola.template.model.DocumentStyles
typealias Expression = app.epistola.template.model.Expression

// V1 types (kept during transition, will be removed in Step 9)
typealias TemplateModel = app.epistola.template.model.TemplateModel
typealias Block = app.epistola.template.model.Block
typealias TextBlock = app.epistola.template.model.TextBlock
typealias ContainerBlock = app.epistola.template.model.ContainerBlock
typealias ConditionalBlock = app.epistola.template.model.ConditionalBlock
typealias LoopBlock = app.epistola.template.model.LoopBlock
typealias ColumnsBlock = app.epistola.template.model.ColumnsBlock
typealias Column = app.epistola.template.model.Column
typealias TableBlock = app.epistola.template.model.TableBlock
typealias BorderStyle = app.epistola.template.model.BorderStyle
typealias TableRow = app.epistola.template.model.TableRow
typealias TableCell = app.epistola.template.model.TableCell
typealias PageBreakBlock = app.epistola.template.model.PageBreakBlock
typealias PageHeaderBlock = app.epistola.template.model.PageHeaderBlock
typealias PageFooterBlock = app.epistola.template.model.PageFooterBlock
