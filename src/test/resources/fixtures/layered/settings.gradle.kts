rootProject.name = "layered"

// Not in alphabetical order on purpose: the guard sorts what it reports.
include(":app")
include(":feature:orders:history")
include(":feature:orders:tracking")
include(":feature:account:profile")
include(":component:auth")
include(":component:orders:history")
include(":shared-component:cart")
include(":shared-component:pricing")
include(":core:ui")
include(":core:network")
include(":sample:playground")
