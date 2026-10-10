# jeddy_tools_native: small C helpers for the cooker that are awkward to call directly through FFM.
# Today it wraps MikkTSpace so tangent generation needs no Java upcalls (DESIGN §6.1, §10.3).
cmake_minimum_required(VERSION 3.20)
project(jeddy_tools_native C)

if(NOT MIKKTSPACE_DIR)
  message(FATAL_ERROR "Pass -DMIKKTSPACE_DIR=<path to a MikkTSpace checkout>")
endif()

add_library(jeddy_tools_native SHARED jeddy_mikktspace.c "${MIKKTSPACE_DIR}/mikktspace.c")
target_include_directories(jeddy_tools_native PRIVATE "${MIKKTSPACE_DIR}")
target_compile_definitions(jeddy_tools_native PRIVATE JEDDY_TOOLS_NATIVE_BUILD)
set_target_properties(jeddy_tools_native PROPERTIES C_VISIBILITY_PRESET hidden C_STANDARD 99)
if(UNIX)
  target_link_libraries(jeddy_tools_native PRIVATE m)
endif()

install(TARGETS jeddy_tools_native RUNTIME DESTINATION bin LIBRARY DESTINATION lib ARCHIVE DESTINATION lib)
install(FILES jeddy_tools_native.h DESTINATION include)
