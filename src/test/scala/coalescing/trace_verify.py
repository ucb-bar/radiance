#!/usr/bin/env python3
import sys

stored_values = {}

"""
An example simulation trace file looks like:

cycle  loadstore  core_id  lane_id  source  addr       data         size
479    STORE      0        1        14      0xffeffc   0x0          4
480    STORE      0        1        15      0xffdffc   0x0          4
481    STORE      0        1        0       0xffcffc   0x0          4
485    STORE      0        1        4       0xffeff8   0x0          4
486    STORE      0        1        5       0xffdff8   0x0          4
487    STORE      0        1        6       0xffcff8   0x0          4
489    STORE      0        1        8       0xfffffc   0x80000030   4

TO RUN:
./trace_verify.py ../../../../../../sims/vcs/vecadd.core1.thread4.trace.coreside.req ../../../../../../sims/vcs/vecadd.core1.thread4.trace.coreside.resp

"""

def get_entries(file_name):
    """
    Parses a trace file and groups all non-empty lines by their cycle number.

    Each line in the file is expected to have at least eight whitespace-separated
    fields, where the first field represents the cycle identifier. Lines sharing
    the same cycle are grouped together in a list.

    Args:
        file_name (str): Path to the trace file to be parsed.

    Returns:
        dict[str, list[str]]: A dictionary mapping each cycle number (as a string)
        to a list of all lines corresponding to that cycle.
    """
    entries = {}
    with open(file_name) as file:
        for line in file:
            line = line.strip()
            
            if not line:
                continue

            cycle, _, _, _, _, _, _, _ = line.split()

            if cycle not in entries:
                entries[cycle] = [line]
            else:
                entries[cycle].append(line)
    return entries

def check_load_match(line_num, line_req, line_resp):
    """
    Verifies that a load request and its corresponding response match in all expected fields.

    This function compares two trace lines—one representing a load request and the other
    representing its response—to ensure they are consistent in operation type, core/lane IDs,
    source, address, data, and size. It also checks that the response occurs after the request
    in cycle order.

    Args:
        line_num (int): The line number of the response being checked (for error reporting).
        line_req (str): The trace line corresponding to the load request.
        line_resp (str): The trace line corresponding to the load response.

    Returns:
        tuple[bool, str | None]:
            A tuple containing:
              - A boolean indicating whether the request and response match.
              - An error message string if a mismatch is found, or None if the check passes.

    Notes:
        - Both `line_req` and `line_resp` must contain eight whitespace-separated fields in the order:
          cycle, op, core_id, lane_id, source, addr, data, size.
        - The global dictionary `stored_values` must contain the expected data for each address (`req_addr`)
          to verify load data correctness.
    """
    req_cycle, req_op, req_core_id, req_lane_id, req_source, req_addr, req_data, req_size = line_req.split()
    resp_cycle, resp_op, resp_core_id, resp_lane_id, resp_source, resp_addr, resp_data, resp_size  = line_resp.split()

    if not (int(req_cycle) < int(resp_cycle)):
        return False, f"LOAD Line {line_num}: incorrect cycle: req={req_cycle}, resp={resp_cycle}."
    if not (req_op == resp_op):
        return False, f"LOAD Line {line_num}: mismatched operation."
    if not (req_core_id == resp_core_id):
        return False, f"LOAD Line {line_num}: mismatched core id."
    if not (req_lane_id == resp_lane_id):
        return False, f"LOAD Line {line_num}: mismatched lane id."
    if not (req_source == resp_source):
        return False, f"LOAD Line {line_num}: mismatched source."
    if not (stored_values[req_addr] == resp_data):
        return False, f"LOAD Line {line_num}: mismatched data from {req_op}: expected {stored_values[req_addr]} but got {resp_data}."
    if not (req_size == resp_size):
        return False, f"LOAD Line {line_num}: mismatched size."

    return True, None

def check_store_match(line_num, line_req, line_resp):
    """
    Verifies that a store request and its corresponding response match across all expected fields.

    This function compares two trace lines—one representing a store request and the other
    representing its response—to ensure they are consistent in operation type, core/lane IDs,
    source, address, and size. It also checks that the response occurs after the request
    in terms of cycle order.

    Args:
        line_num (int): The line number of the response being checked (used in error messages).
        line_req (str): The trace line corresponding to the store request.
        line_resp (str): The trace line corresponding to the store response.

    Returns:
        tuple[bool, str | None]:
            A tuple containing:
              - A boolean indicating whether the request and response match.
              - An error message string if a mismatch is detected, or None if all checks pass.

    Notes:
        - Both `line_req` and `line_resp` must contain eight whitespace-separated fields in the order:
          cycle, op, core_id, lane_id, source, addr, data, size.
        - Unlike `check_load_match`, this function does not verify the stored data value.
    """
    req_cycle, req_op, req_core_id, req_lane_id, req_source, req_addr, req_data, req_size = line_req.split()
    resp_cycle, resp_op, resp_core_id, resp_lane_id, resp_source, resp_addr, resp_data, resp_size  = line_resp.split()

    if not (int(req_cycle) < int(resp_cycle)):
        return False, f"STORE Line {line_num}: incorrect cycle: req={req_cycle}, resp={resp_cycle}."
    if not (req_op == resp_op):
        return False, f"STORE Line {line_num}: mismatched operation."
    if not (req_core_id == resp_core_id):
        return False, f"STORE Line {line_num}: mismatched core id."
    if not (req_lane_id == resp_lane_id):
        return False, f"STORE Line {line_num}: mismatched lane id."
    if not (req_source == resp_source):
        return False, f"STORE Line {line_num}: mismatched source."
    if not (req_size == resp_size):
        return False, f"STORE Line {line_num}: mismatched size."

    return True, None

def verify(coreside_req, coreside_resp):
    """
    Verifies the correctness of request–response pairs between core-side trace files.

    This function compares each request line in the core-side request trace file
    (`coreside_req`) with its corresponding response line in the core-side response
    trace file (`coreside_resp`) to ensure that all STORE and LOAD operations match
    according to expected conditions.

    For each line pair:
      - STORE operations are checked using `check_store_match()`.
      - LOAD operations are checked using `check_load_match()`, ensuring that
        returned data matches previously stored values.
      - If multiple requests occur in the same cycle, each candidate request is
        compared until a valid match is found.
      - If no valid match is found, an error message is printed.

    The function prints `"All requests match responses."` if no mismatches occur.

    Globals:
        coreside_req (str): Path to the core-side request trace file.
        coreside_resp (str): Path to the core-side response trace file.
        stored_values (dict): A dictionary mapping addresses to the most recently stored data values.
                              Updated during STORE operations and used to validate LOAD responses.

    Returns:
        None
            Prints detailed mismatch messages during execution.
            Prints a success message if all request–response pairs are verified successfully.

    Notes:
        - Each line in the trace files must contain eight whitespace-separated fields:
          cycle, op, core_id, lane_id, source, addr, data, size.
        - The function uses `get_entries()` to group all request lines by their cycle
          for handling cases with multiple requests in the same cycle.
        - Assumes both trace files are aligned so that corresponding requests and
          responses appear in the same order.
    """
    error = False
    req_entries = get_entries(coreside_req)
    
    with open(coreside_req) as f_req, open(coreside_resp) as f_resp:
        for line_num, (line_req, line_resp) in enumerate(zip(f_req, f_resp), start=1):
            line_req = line_req.strip()
            line_resp = line_resp.strip()

            if not line_req or not line_resp:
                continue

            req_cycle, req_op, _, _, _, req_addr, req_data, _ = line_req.split()
            _, resp_op, _, _, _, _, _, _  = line_resp.split()

            if req_op == "STORE":
                if len(req_entries[req_cycle]) > 1:
                    for line in req_entries[req_cycle]:
                        is_match, err_msg = check_store_match(line_num, line, line_resp)
                        if is_match:
                            stored_values[req_addr] = req_data
                            break
                    else:
                        print(err_msg)
                        error = True
                else:
                    is_match, err_msg = check_store_match(line_num, line_req, line_resp)
                    if not is_match:
                        print(err_msg)
                        error = True
                    else:
                        stored_values[req_addr] = req_data
            elif req_op == "LOAD":
                if req_addr in stored_values:
                    if len(req_entries[req_cycle]) > 1:
                        for line in req_entries[req_cycle]:
                            is_match, err_msg = check_load_match(line_num, line, line_resp)
                            if is_match:
                                break
                        else:
                            print(err_msg)
                            error = True
                    else:
                        is_match, err_msg = check_load_match(line_num, line_req, line_resp)
                        if not is_match:
                            print(err_msg)
                            error = True
                        

    if not error:
        print("All requests match responses.")

def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <coreside_req> <coreside_resp>")
        sys.exit(1)

    coreside_req = sys.argv[1]
    coreside_resp = sys.argv[2]

    verify(coreside_req, coreside_resp)

if __name__ == "__main__":
    main()
